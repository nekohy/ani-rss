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
import java.util.regex.Pattern;
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

    private static final String LANG = "(?:sc|tc|chs|cht|gb|big5|zh|chi|zho|hans|hant|cn|tw|hk|jp|ja|jpn|en|eng)";
    /**
     * 字幕的语言标记: sc, tcjp, chs_jp, zh-Hans ... (不含 WEB-DL, AAC)
     */
    private static final Pattern LANG_REG = Pattern.compile("^" + LANG + "(?:[-_&]?" + LANG + ")*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SC_REG = Pattern.compile("简|chs|\\bsc\\b|\\bGB\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TC_REG = Pattern.compile("繁|cht|\\btc\\b|BIG5", Pattern.CASE_INSENSITIVE);

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

            // 已有的这一集: 视频和字幕 reName.*
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
            List<OpenListFileInfo> subtitleList = files.stream()
                    .filter(fileInfo -> FileUtils.isSubtitleFormat(fileInfo.getName()))
                    .toList();

            Map<OpenListFileInfo, String> targets = targets(rename ? reName : null, videoFile, subtitleList);

            // 洗版, 删除已有的这一集
            if (!versions.isEmpty()) {
                log.info("已开启备用RSS, 自动删除 {}/{}", savePath, versions);
                openListUtil.fsRemove(savePath, versions);
            }

            // 在各自的目录里改名, 再移到季目录
            Map<String, List<OpenListFileInfo>> dirs = targets.keySet()
                    .stream()
                    .collect(Collectors.groupingBy(OpenListFileInfo::getPath, LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<OpenListFileInfo>> entry : dirs.entrySet()) {
                String dir = entry.getKey();
                List<OpenListFileInfo> list = entry.getValue();
                List<Map<String, String>> renameObjects = list.stream()
                        .filter(fileInfo -> !fileInfo.getName().equals(targets.get(fileInfo)))
                        .map(fileInfo -> {
                            log.info("重命名 {} ==> {}", fileInfo.getName(), targets.get(fileInfo));
                            return Map.of(
                                    "src_name", fileInfo.getName(),
                                    "new_name", targets.get(fileInfo)
                            );
                        }).toList();
                if (!renameObjects.isEmpty()) {
                    openListUtil.fsBatchRename(renameObjects, dir);
                }
                if (!dir.equals(stage)) {
                    openListUtil.fsMove(dir, stage, list.stream().map(targets::get).toList());
                }
            }

            // 删除种子带来的文件夹和其余文件
            List<String> residual = added.stream()
                    .filter(fileInfo -> fileInfo.getIsDir() || !targets.containsKey(fileInfo))
                    .map(OpenListFileInfo::getName)
                    .toList();
            if (!residual.isEmpty()) {
                log.info("删除残留 {}/{}", stage, residual);
                openListUtil.fsRemove(stage, residual);
            }

            // 移到保存位置, 等 OpenList 的移动任务结束后删除这一集的文件夹
            if (!stage.equals(savePath)) {
                List<String> names = List.copyOf(targets.values());
                String error;
                try {
                    openListUtil.mkdir(savePath);
                    List<String> tids = openListUtil.fsMove(stage, savePath, names);
                    log.info("移动 {}/{} ==> {}", stage, names, savePath);
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

    /**
     * 文件 -> 新名字: 视频 reName.ext, 字幕 reName.语言.ext; reName 为 null 时保留原名
     */
    private static Map<OpenListFileInfo, String> targets(String reName, OpenListFileInfo videoFile, List<OpenListFileInfo> subtitleList) {
        Map<OpenListFileInfo, String> targets = new LinkedHashMap<>();
        if (Objects.isNull(reName)) {
            targets.put(videoFile, videoFile.getName());
            subtitleList.forEach(fileInfo -> targets.put(fileInfo, fileInfo.getName()));
            return targets;
        }
        targets.put(videoFile, reName + "." + FileUtil.extName(videoFile.getName()));

        Map<OpenListFileInfo, String> subtitles = new LinkedHashMap<>();
        for (OpenListFileInfo fileInfo : subtitleList) {
            String lang = subtitleLang(fileInfo.getName());
            subtitles.put(fileInfo, reName + (lang.isEmpty() ? "" : "." + lang) + "." + FileUtil.extName(fileInfo.getName()));
        }
        // 同一个语言标记出现两次 (chs, chs_annotated) 时保留各自原来的后缀
        String own = FileUtil.mainName(videoFile.getName()) + ".";
        subtitles.forEach((fileInfo, target) -> {
            if (Collections.frequency(subtitles.values(), target) > 1) {
                String name = fileInfo.getName();
                target = reName + "." + (name.startsWith(own) ? name.substring(own.length()) : name);
            }
            targets.put(fileInfo, target);
        });
        return targets;
    }

    /**
     * 字幕的语言标记, 没有时为空
     */
    private static String subtitleLang(String name) {
        String stem = FileUtil.mainName(name);
        String tag = FileUtil.extName(stem);
        if (StrUtil.isNotBlank(tag) && LANG_REG.matcher(tag).matches()) {
            return tag;
        }
        boolean sc = SC_REG.matcher(stem).find();
        boolean tc = TC_REG.matcher(stem).find();
        if (sc && tc) {
            return "zh";
        }
        return sc ? "chs" : tc ? "cht" : "";
    }
}
