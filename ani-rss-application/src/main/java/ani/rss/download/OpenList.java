package ani.rss.download;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.config.OpenListConfig;
import ani.rss.entity.*;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.OpenListUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenList 离线下载: 下载到季目录, 新文件靠下载前后各列一次目录找出, 原地改名, 种子带的文件夹移出后删除。
 * 设置了离线下载目录时在其中每集一个文件夹里做这些, 再移到保存位置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenList {
    private static final Config CONFIG = ConfigUtil.CONFIG;

    private final OpenListUtil openListUtil = OpenListUtil.getInstance(new OpenListConfig() {
        @Override
        public String getServer() {
            return CONFIG.getDownloadToolHost();
        }

        @Override
        public String getApiKey() {
            return CONFIG.getDownloadToolPassword();
        }
    });

    public Boolean login(Boolean test, Config config) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            return OpenListUtil.getInstance(host, password).test();
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("登录 OpenList 失败 {}", message);
        }
        return false;
    }


    /**
     * OpenList 的离线下载和移动任务
     */
    public List<OpenListTaskInfo> tasks() {
        List<OpenListTaskInfo> tasks = new ArrayList<>();
        for (String type : List.of("offline_download", "move")) {
            for (String status : List.of("undone", "done")) {
                tasks.addAll(openListUtil.taskList(type, status));
            }
        }
        return tasks;
    }

    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        // windows 真该死啊
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        // 离线下载目录, 未设置时就是保存位置
        String stage = StrUtil.removeSuffix(StrUtil.trim(CONFIG.getOpenListOfflinePath()), "/");
        stage = StrUtil.isBlank(stage) ? savePath : ReUtil.replaceAll(stage, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String reName = item.getReName();
        Boolean rename = CONFIG.getRename();
        Boolean delete = CONFIG.getDelete();
        // 洗版: 开启备用RSS、自动删除且不共存时替换已有的这一集, 否则跳过
        boolean replace = CONFIG.getStandbyRss() && delete && !CONFIG.getCoexist();
        try {
            List<OpenListFileInfo> existing;
            if (stage.equals(savePath)) {
                openListUtil.mkdir(savePath);
                existing = openListUtil.list(savePath);
            } else {
                // 保存位置在别的网盘, 移动时再创建 (OneDrive 会限流)
                existing = openListUtil.listIfExists(savePath);
            }

            // 已有的这一集 reName.*
            List<String> versions = rename ? existing.stream()
                    .filter(fileInfo -> !fileInfo.getIsDir())
                    .map(OpenListFileInfo::getName)
                    .filter(name -> name.startsWith(reName + "."))
                    .toList() : List.of();
            if (!replace && versions.stream().anyMatch(FileUtils::isVideoFormat)) {
                log.info("已存在 {}/{}, 跳过", savePath, reName);
                return true;
            }

            List<OpenListFileInfo> before = existing;
            String offlineDir = stage;
            if (!stage.equals(savePath)) {
                stage = stage + "/" + reName;
                openListUtil.mkdir(stage);
                before = List.of();
            }

            // 删除残留任务
            openListUtil.deleteResidualTasks(magnet);

            String tid;
            try {
                tid = openListUtil.fsAddOfflineDownload(magnet, stage, CONFIG.getProvider());
                log.info("添加离线下载成功 {}", reName);
            } catch (Exception e) {
                log.error("添加离线下载失败 {}", reName);
                throw new IllegalStateException("添加离线下载失败 " + reName);
            }

            // 记录开始时间
            DateTime startTime = DateTime.now();

            // 重试次数
            long retry = 0;
            while (true) {
                Integer openListDownloadTimeout = CONFIG.getOpenListDownloadTimeout();
                Long openListDownloadRetryNumber = CONFIG.getOpenListDownloadRetryNumber();

                DateTime endTime = DateUtil.offsetMinute(startTime, openListDownloadTimeout);
                DateTime currentTime = DateTime.now();
                if (currentTime.getTime() >= endTime.getTime()) {
                    // 超过下载超时限制
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, openListDownloadTimeout);
                    return false;
                }

                Optional<OpenListTaskInfo> taskInfoOpt = openListUtil.taskInfo(tid);

                if (taskInfoOpt.isEmpty()) {
                    continue;
                }

                OpenListTaskInfo taskInfo = taskInfoOpt.get();
                OpenListTaskInfo.State state = taskInfo.getState();
                String error = taskInfo.getError();

                // errored 重试
                if (
                        List.of(
                                OpenListTaskInfo.State.Error,
                                OpenListTaskInfo.State.Failing,
                                OpenListTaskInfo.State.Failed
                        ).contains(state)
                ) {
                    // OpenList 自己会重试, Failed 了再处理
                    if (state != OpenListTaskInfo.State.Failed) {
                        continue;
                    }
                    // 已到达最大重试次数 5 次, -1 不限制
                    if (openListDownloadRetryNumber > -1) {
                        if (retry >= openListDownloadRetryNumber) {
                            // bug fix: 新资源下载完成后，OpenList 状态可能未及时刷新
                            // 此处通过检查文件是否存在来兜底，存在则直接继续后续逻辑
                            boolean downloaded = files(stage, added(stage, before))
                                    .stream()
                                    .anyMatch(fileInfo -> FileUtils.isVideoFormat(fileInfo.getName()));
                            if (downloaded) {
                                log.info("资源已下载完毕，OpenList 可能处于卡死状态，此处跳过");
                                break;
                            }
                            log.error("离线下载失败 {}", error);
                            return false;
                        }
                        retry++;
                        log.info("离线任务正在进行重试 {}, 当前重试次数 {}, 最大重试次数 {}", tid, retry, openListDownloadRetryNumber);
                    }
                    openListUtil.taskRetry(tid);
                    continue;
                }

                if (
                        List.of(
                                OpenListTaskInfo.State.Canceling,
                                OpenListTaskInfo.State.Canceled
                        ).contains(state)
                ) {
                    log.error("离线任务已被取消 {}", reName);
                    return false;
                }

                // 成功
                if (state == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
            }

            if (delete) {
                log.info("离线下载完成, 自动删除已完成任务");
                openListUtil.taskDelete(tid);
            }

            // 这次下载新出现的文件和文件夹
            List<OpenListFileInfo> added = added(stage, before);
            List<OpenListFileInfo> files = files(stage, added);

            // 取大小最大的一个视频文件
            Optional<OpenListFileInfo> videoFileOpt = files.stream()
                    .filter(fileInfo -> FileUtils.isVideoFormat(fileInfo.getName()))
                    .max(Comparator.comparing(fileInfo -> ObjectUtil.defaultIfNull(fileInfo.getSize(), 0L)));

            if (videoFileOpt.isEmpty()) {
                log.error("离线下载完成, 但没有找到视频 {}", reName);
                return false;
            }
            OpenListFileInfo videoFile = videoFileOpt.get();
            String target = rename ? reName + "." + FileUtil.extName(videoFile.getName()) : videoFile.getName();

            // 洗版, 删除已有的这一集
            if (!versions.isEmpty()) {
                log.info("已开启备用RSS, 自动删除 {}/{}", savePath, versions);
                openListUtil.fsRemove(savePath, versions);
            }

            // 改名, 在种子带的文件夹里时移到季目录
            String dir = videoFile.getPath();
            if (!videoFile.getName().equals(target)) {
                log.info("重命名 {} ==> {}", videoFile.getName(), target);
                openListUtil.fsBatchRename(List.of(Map.of("src_name", videoFile.getName(), "new_name", target)), dir);
            }
            if (!dir.equals(stage)) {
                openListUtil.fsMove(dir, stage, List.of(target));
            }

            // 删除种子带来的文件夹和其余文件
            List<String> residual = added.stream()
                    .filter(fileInfo -> !fileInfo.equals(videoFile))
                    .map(OpenListFileInfo::getName)
                    .toList();
            if (!residual.isEmpty()) {
                log.info("删除残留 {}/{}", stage, residual);
                openListUtil.fsRemove(stage, residual);
            }

            // 移到保存位置, 等 OpenList 的移动任务结束后删除这一集的文件夹
            if (!stage.equals(savePath)) {
                String error;
                try {
                    openListUtil.mkdir(savePath);
                    List<String> tids = openListUtil.fsMove(stage, savePath, List.of(target));
                    log.info("移动 {}/{} ==> {}", stage, target, savePath);
                    error = awaitMove(tids);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (Objects.nonNull(error)) {
                    String message = StrFormatter.format("{} 已下载到 {}, 移动到 {} 失败: {}", reName, stage, savePath, error);
                    log.error(message);
                    NotificationUtil.send(CONFIG, ani, message, NotificationStatusEnum.ERROR);
                    return true;
                }
                openListUtil.fsRemove(offlineDir, List.of(reName));
            }

            NotificationUtil.send(CONFIG, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END
            );
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * 等移动任务结束, 返回失败原因, 都成功时为 null
     */
    private String awaitMove(List<String> tids) {
        DateTime endTime = DateUtil.offsetMinute(DateTime.now(), CONFIG.getOpenListDownloadTimeout());
        for (String tid : tids) {
            while (true) {
                if (DateTime.now().isAfter(endTime)) {
                    return CONFIG.getOpenListDownloadTimeout() + " 分钟还未移完";
                }
                Optional<OpenListTaskInfo> taskInfoOpt = openListUtil.taskInfo("move", tid);
                if (taskInfoOpt.isEmpty()) {
                    continue;
                }
                OpenListTaskInfo.State state = taskInfoOpt.get().getState();
                if (state == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
                if (List.of(OpenListTaskInfo.State.Failed, OpenListTaskInfo.State.Canceled).contains(state)) {
                    return StrUtil.blankToDefault(taskInfoOpt.get().getError(), state.name());
                }
            }
        }
        return null;
    }

    /**
     * 季目录里这次下载新出现的文件和文件夹
     */
    private List<OpenListFileInfo> added(String savePath, List<OpenListFileInfo> before) {
        Set<String> names = before.stream()
                .map(OpenListFileInfo::getName)
                .collect(Collectors.toSet());
        return openListUtil.list(savePath)
                .stream()
                .filter(fileInfo -> !names.contains(fileInfo.getName()))
                .toList();
    }

    /**
     * 新出现的文件, 文件夹展开
     */
    private List<OpenListFileInfo> files(String savePath, List<OpenListFileInfo> added) {
        return added.stream()
                .flatMap(fileInfo -> fileInfo.getIsDir() ?
                        openListUtil.findFiles(savePath + "/" + fileInfo.getName()).stream() :
                        Stream.of(fileInfo))
                .toList();
    }
}
