package ani.rss.service;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PinyinUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.other.*;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.util.*;

/**
 * 下载的主要逻辑
 */
@Slf4j
@Service
public class DownloadService {
    private static final Config CONFIG = ConfigUtil.CONFIG;
    private static final Object LOCK = new Object();

    /**
     * 下载动漫
     *
     * @param ani 订阅
     */
    @Synchronized("LOCK")
    public void downloadAni(Ani ani) {
        Boolean autoDisabled = CONFIG.getAutoDisabled();
        Integer delayedDownload = CONFIG.getDelayedDownload();

        String title = ani.getTitle();
        Integer season = ani.getSeason();
        Boolean downloadNew = ani.getDownloadNew();
        List<Double> notDownload = ani.getNotDownload();

        int currentDownloadCount = 0;
        List<Item> items = ItemsUtil.getItems(ani);

        ItemsUtil.omit(ani, items);
        log.debug("{} 共 {} 个", title, items.size());

        String savePath = getDownloadPath(ani);

        ItemsUtil.procrastinating(ani, items);

        // 实时保存文件
        boolean sync = false;

        for (Item item : items) {
            log.debug(JSONUtil.formatJsonStr(GsonStatic.toJson(item)));
            String reName = item.getReName();
            File torrent = TorrentUtil.getTorrent(ani, item);
            boolean master = item.getMaster();

            Double episode = item.getEpisode();
            // .5 集
            boolean is5 = ItemsUtil.is5(episode);

            // 已经下载过
            if (torrent.exists()) {
                log.debug("种子记录已存在 {}", reName);
                if (master && !is5) {
                    currentDownloadCount++;
                }
                continue;
            }

            if (notDownload.contains(episode)) {
                if (master && !is5) {
                    currentDownloadCount++;
                }
                log.debug("已被禁止下载: {}", reName);
                continue;
            }

            // 只下载最新集
            if (downloadNew) {
                Item newItem = items.get(items.size() - 1);

                // 日期一致也可下载, 防止字幕组同时发多集
                Date pubDate = item.getPubDate();
                Date newPubDate = newItem.getPubDate();
                if (Objects.nonNull(pubDate) && Objects.nonNull(newPubDate)) {
                    String pubDateFormat = DateUtil.format(pubDate, "yyyy-MM-dd");
                    String newPubDateFormat = DateUtil.format(newPubDate, "yyyy-MM-dd");
                    // 日期不一致则跳过
                    if (!pubDateFormat.equals(newPubDateFormat)) {
                        if (master && !is5) {
                            currentDownloadCount++;
                        }
                        continue;
                    }
                } else if (item != newItem) {
                    if (master && !is5) {
                        currentDownloadCount++;
                    }
                    continue;
                }
            }

            Date pubDate = item.getPubDate();
            if (Objects.nonNull(pubDate) && delayedDownload > 0) {
                Date now = DateUtil.offset(new Date(), DateField.MINUTE, -delayedDownload);
                if (now.getTime() < pubDate.getTime()) {
                    log.info("延迟下载 {}", reName);
                    continue;
                }
            }

            File saveTorrent = TorrentUtil.saveTorrent(ani, item);

            if (!saveTorrent.exists()) {
                // 种子下载失败
                continue;
            }

            if (!AniUtil.ANI_LIST.contains(ani)) {
                return;
            }

            sync = true;

            download(ani, item, savePath, saveTorrent);

            if (master && !is5) {
                currentDownloadCount++;
            }
        }

        if (sync) {
            int size = ItemsUtil.currentEpisodeNumber(ani, items);
            // 更新当前集数
            ani.setCurrentEpisodeNumber(size);
            // 更新下载时间
            ani.setLastDownloadTime(System.currentTimeMillis());
            AniUtil.sync();
        }

        if (!autoDisabled) {
            return;
        }
        int totalEpisodeNumber = ani.getTotalEpisodeNumber();
        if (totalEpisodeNumber < 1) {
            return;
        }
        if (currentDownloadCount >= totalEpisodeNumber) {
            log.info("{} 第 {} 季 共 {} 集 已全部下载完成, 自动停止订阅", title, season, totalEpisodeNumber);
            NotificationUtil.send(CONFIG, ani, StrFormatter.format("{} 订阅已完结", title), NotificationStatusEnum.COMPLETED);
            ani.setEnable(false);
            AniUtil.sync();
        }
    }

    /**
     * 下载
     *
     * @param ani         订阅
     * @param item        资源项
     * @param savePath    保存位置
     * @param torrentFile 种子文件
     */
    public void download(Ani ani, Item item, String savePath, File torrentFile) {
        ani = ObjectUtil.clone(ani);

        String name = item.getReName();
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");
        ani.setSubgroup(subgroup);

        log.info("添加下载 {}", name);

        if (!torrentFile.exists()) {
            log.error("种子下载出现问题 {} {}", name, torrentFile);
            return;
        }
        ThreadUtil.sleep(1000);
        savePath = FileUtils.getAbsolutePath(savePath);

        String text = StrFormatter.format("{} 已更新", name);
        if (!master) {
            text = StrFormatter.format("(备用RSS) {}", text);
        }
        NotificationUtil.send(CONFIG, ani, item.getEpisode(), text, NotificationStatusEnum.DOWNLOAD_START);

        Integer downloadRetry = CONFIG.getDownloadRetry();
        for (int i = 1; i <= downloadRetry; i++) {
            try {
                if (TorrentUtil.download(ani, item, savePath, torrentFile)) {
                    return;
                }
            } catch (Exception e) {
                String message = ExceptionUtils.getMessage(e);
                log.error(message, e);
            }
            log.error("{} 下载失败将进行重试, 当前重试次数为{}次", name, i);
        }

        // 删除下载失败的种子, 下次轮询仍会重试
        FileUtil.del(torrentFile);

        log.error("{} 添加失败，疑似为坏种", name);
        NotificationUtil.send(CONFIG, ani, item.getEpisode(),
                StrFormatter.format("{} 添加失败，疑似为坏种", name),
                NotificationStatusEnum.ERROR);
    }

    /**
     * 获取下载位置
     *
     * @param ani 订阅
     * @return 下载位置
     */
    public String getDownloadPath(Ani ani) {
        return getDownloadPath(ani, CONFIG);
    }

    /**
     * 获取下载位置
     *
     * @param ani                  订阅
     * @param downloadPathTemplate 下载位置模板
     * @return 下载位置
     */
    public String getDownloadPath(Ani ani, String downloadPathTemplate) {
        Ani clone = ObjectUtil.clone(ani);
        clone.setCustomDownloadPathTemplate(downloadPathTemplate)
                .setCustomDownloadPath(true);
        return getDownloadPath(clone, CONFIG);
    }

    /**
     * 获取下载位置
     *
     * @param ani 订阅
     * @return 下载位置
     */
    public String getDownloadPath(Ani ani, Config config) {
        Boolean customDownloadPath = ani.getCustomDownloadPath();
        String customDownloadPathTemplate = ani.getCustomDownloadPathTemplate();
        Boolean ova = ani.getOva();

        String downloadPathTemplate = config.getDownloadPathTemplate();
        String ovaDownloadPathTemplate = config.getOvaDownloadPathTemplate();
        if (ova && StrUtil.isNotBlank(ovaDownloadPathTemplate)) {
            // 剧场版位置
            downloadPathTemplate = ovaDownloadPathTemplate;
        }

        if (customDownloadPath && StrUtil.isNotBlank(customDownloadPathTemplate)) {
            // 自定义下载位置
            downloadPathTemplate = StrUtil.split(customDownloadPathTemplate, "\n", true, true)
                    .stream()
                    .map(FileUtils::getAbsolutePath)
                    .findFirst()
                    .orElse(downloadPathTemplate);
        }

        String title = ani.getTitle().trim();

        String letter = PinyinUtils.getPinyinInitialLetters(title);

        downloadPathTemplate = downloadPathTemplate.replace("${letter}", letter);

        Date releaseDate = ani.getReleaseDate();
        Tmdb tmdb = ani.getTmdb();

        Date tmdbDate = Optional.ofNullable(tmdb)
                .map(Tmdb::getDate)
                .orElse(releaseDate);

        int tmdbYear = DateUtil.year(tmdbDate);
        int year = DateUtil.year(releaseDate);
        int month = DateUtil.month(releaseDate) + 1;
        String monthFormat = String.format("%02d", month);

        // 季度
        if (
                downloadPathTemplate.contains("${quarter}") ||
                        downloadPathTemplate.contains("${quarterFormat}") ||
                        downloadPathTemplate.contains("${quarterName}")
        ) {
            int quarter;
            String quarterName;
            /*
            https://github.com/wushuo894/ani-rss/pull/451
            优化季度判断规则，避免将月底先行播放的番归类到上个季度
            */
            if (List.of(12, 1, 2).contains(month)) {
                if (month == 12) {
                    // 当使用季度信息, 并且月份等于12时, 年份自动 +1。避免年份与月份不一致
                    year++;
                }
                quarter = 1;
                quarterName = "冬";
            } else if (List.of(3, 4, 5).contains(month)) {
                quarter = 4;
                quarterName = "春";
            } else if (List.of(6, 7, 8).contains(month)) {
                quarter = 7;
                quarterName = "夏";
            } else {
                quarter = 10;
                quarterName = "秋";
            }
            String quarterFormat = String.format("%02d", quarter);
            downloadPathTemplate = downloadPathTemplate.replace("${quarter}", String.valueOf(quarter));
            downloadPathTemplate = downloadPathTemplate.replace("${quarterFormat}", quarterFormat);
            downloadPathTemplate = downloadPathTemplate.replace("${quarterName}", quarterName);
        }

        downloadPathTemplate = downloadPathTemplate.replace("${tmdbYear}", String.valueOf(tmdbYear));
        downloadPathTemplate = downloadPathTemplate.replace("${year}", String.valueOf(year));
        downloadPathTemplate = downloadPathTemplate.replace("${month}", String.valueOf(month));
        downloadPathTemplate = downloadPathTemplate.replace("${monthFormat}", monthFormat);

        int season = ani.getSeason();
        String seasonFormat = String.format("%02d", season);

        downloadPathTemplate = downloadPathTemplate.replace("${season}", String.valueOf(season));
        downloadPathTemplate = downloadPathTemplate.replace("${seasonFormat}", seasonFormat);

        String bgmId = BgmUtil.getSubjectId(ani);
        downloadPathTemplate = downloadPathTemplate.replace("${bgmId}", bgmId);
        downloadPathTemplate = BgmUtil.replaceShow(downloadPathTemplate, ani);

        List<Func1<Ani, Object>> list = List.of(
                Ani::getTitle,
                Ani::getThemoviedbName,
                Ani::getSubgroup
        );

        downloadPathTemplate = RenameUtil.replaceField(downloadPathTemplate, ani, list);

        String tmdbId = Opt.ofNullable(ani.getTmdb())
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("");

        downloadPathTemplate = downloadPathTemplate.replace("${tmdbid}", tmdbId);

        if (downloadPathTemplate.contains("${jpTitle}")) {
            String jpTitle = RenameUtil.getJpTitle(ani);
            downloadPathTemplate = downloadPathTemplate.replace("${jpTitle}", jpTitle);
        }

        return FileUtils.getAbsolutePath(downloadPathTemplate);
    }
}
