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
 * OpenList 离线下载: 直接下载到下载位置 (季目录)。
 * 单文件的种子在原地改名; 带文件夹的种子把视频和字幕移到季目录, 再删除这个文件夹。
 * 新文件靠下载前后各列一次季目录找出, 下载是一个接一个进行的 (DownloadService.downloadAni 加了锁)。
 * 设置了离线下载目录时 (保存位置不在 Driver 所在的网盘), 上面这些在离线下载目录里做, 最后把视频和字幕移到保存位置。
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
            openListUtil.mkdir(savePath);
            List<OpenListFileInfo> existing = openListUtil.list(savePath);

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
            if (!stage.equals(savePath)) {
                openListUtil.mkdir(stage);
                before = openListUtil.list(stage);
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
                    // 网盘的云下载里已有这个链接 (115: 10008), 重试不会成功
                    if (StrUtil.containsAny(error, "10008", "任务已存在")) {
                        throw new TaskExistsException(reName);
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

            // 在各自的目录里改名, 再移到季目录 (离线下载目录)
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

            // 移到保存位置, 跨网盘时 OpenList 在后台复制完再删除源文件
            if (!stage.equals(savePath)) {
                List<String> names = List.copyOf(targets.values());
                try {
                    openListUtil.fsMove(stage, savePath, names);
                    log.info("移动 {}/{} ==> {}", stage, names, savePath);
                } catch (Exception e) {
                    String message = StrFormatter.format("{} 已下载到 {}, 移动到 {} 失败: {}", reName, stage, savePath, e.getMessage());
                    log.error(message, e);
                    NotificationUtil.send(CONFIG, ani, message, NotificationStatusEnum.ERROR);
                    return true;
                }
            }

            NotificationUtil.send(CONFIG, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END
            );
            return true;
        } catch (TaskExistsException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    /**
     * 网盘的云下载列表里已有这个链接: OpenList 删不掉网盘里的任务, 重试也不会成功
     */
    public static class TaskExistsException extends IllegalStateException {
        public TaskExistsException(String reName) {
            super(StrFormatter.format("{} 离线下载失败: 网盘的云下载里已有这个链接 (任务已存在), 请在网盘里删除这个任务, 下次刷新 RSS 时会重新下载", reName));
        }
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
