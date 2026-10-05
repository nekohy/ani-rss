package ani.rss.notification;

import ani.rss.commons.NumberFormatUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Func1;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface BaseNotification {

    Pattern PATH_SLICE = Pattern.compile("\\$\\{downloadPath\\[(-?\\d*)(:?)(-?\\d*)]}");

    private static String slice(String path, String start, boolean range, String end) {
        List<String> parts = StrUtil.split(path, "/", true, true);
        int n = parts.size();
        if (!range && !start.isEmpty()) {
            int i = Integer.parseInt(start);
            i = i < 0 ? i + n : i;
            return i >= 0 && i < n ? parts.get(i) : "";
        }
        int from = start.isEmpty() ? 0 : Integer.parseInt(start);
        int to = end.isEmpty() ? n : Integer.parseInt(end);
        from = Math.clamp(from < 0 ? from + n : from, 0, n);
        to = Math.clamp(to < 0 ? to + n : to, 0, n);
        return from < to ? String.join("/", parts.subList(from, to)) : "";
    }

    /**
     * 测试
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    void test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum);

    /**
     * 发送通知
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 是否成功
     */
    Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum);

    /**
     * 替换通知模版
     *
     * @param ani                    订阅
     * @param notificationConfig     通知设置
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 替换后的通知模版
     */
    default String replaceNotificationTemplate(Ani ani, NotificationConfig notificationConfig, String text, NotificationStatusEnum notificationStatusEnum) {
        String notificationTemplate = notificationConfig.getNotificationTemplate();

        String comment = Opt.ofNullable(notificationConfig)
                .map(NotificationConfig::getComment)
                .filter(StrUtil::isNotBlank)
                .orElse("无备注");

        notificationTemplate = notificationTemplate.replace("${comment}", comment);

        return replaceNotificationTemplate(ani, notificationTemplate, text, notificationStatusEnum);
    }

    /**
     * 替换通知模版
     *
     * @param ani                    订阅
     * @param notificationTemplate   通知模版
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 替换后的通知模版
     */
    default String replaceNotificationTemplate(Ani ani, String notificationTemplate, String text, NotificationStatusEnum notificationStatusEnum) {
        notificationTemplate = notificationTemplate.replace("${text}", text);

        // 集数
        double episode = 1.0;
        Double itemEpisode = NotificationUtil.EPISODE.get();
        if (Objects.nonNull(itemEpisode)) {
            episode = itemEpisode;
        } else if (ReUtil.contains(StringEnum.SEASON_REG, text)) {
            episode = Double.parseDouble(ReUtil.get(StringEnum.SEASON_REG, text, 2));
        }

        String episodeFormat = String.format("%02d", (int) episode);

        // x.5
        if (ItemsUtil.is5(episode)) {
            episodeFormat = episodeFormat + ".5";
        }

        notificationTemplate = notificationTemplate.replace("${episode}",
                NumberFormatUtils.format(episode, 1, 0)
        );
        notificationTemplate = notificationTemplate.replace("${episodeFormat}", episodeFormat);

        if (notificationTemplate.contains("${bgmId}")) {
            notificationTemplate = notificationTemplate.replace("${bgmId}", BgmUtil.getSubjectId(ani));
        }
        notificationTemplate = BgmUtil.replaceShow(notificationTemplate, ani);
        if (notificationTemplate.contains("${bgmEpisode}")) {
            notificationTemplate = notificationTemplate.replace("${bgmEpisode}", BgmUtil.getSortFormat(ani, episode));
        }


        Date releaseDate = ani.getReleaseDate();
        int year = DateUtil.year(releaseDate);
        int month = DateUtil.month(releaseDate) + 1;
        int date = DateUtil.dayOfMonth(releaseDate);

        notificationTemplate = notificationTemplate.replace("${year}", String.valueOf(year));
        notificationTemplate = notificationTemplate.replace("${month}", String.valueOf(month));
        notificationTemplate = notificationTemplate.replace("${date}", String.valueOf(date));

        List<Func1<Ani, Object>> list = List.of(
                Ani::getTitle,
                Ani::getScore,
                Ani::getSeason,
                Ani::getThemoviedbName,
                Ani::getBgmUrl,
                Ani::getCurrentEpisodeNumber,
                Ani::getTotalEpisodeNumber,
                Ani::getSubgroup
        );

        int season = ani.getSeason();
        String seasonFormat = String.format("%02d", season);
        notificationTemplate = notificationTemplate.replace("${seasonFormat}", seasonFormat);

        notificationTemplate = RenameUtil.replaceField(notificationTemplate, ani, list);

        String tmdbId = Optional.of(ani)
                .map(Ani::getTmdb)
                .map(Tmdb::getId)
                .filter(StrUtil::isNotBlank)
                .orElse("");
        notificationTemplate = notificationTemplate.replace("${tmdbid}", tmdbId);

        String tmdbUrl = "";
        if (StrUtil.isNotBlank(tmdbId)) {
            Boolean ova = Opt.ofNullable(ani)
                    .map(Ani::getOva)
                    .orElse(false);
            String type = ova ? "movie" : "tv";
            tmdbUrl = StrFormatter.format("https://www.themoviedb.org/{}/{}", type, tmdbId);
        }
        notificationTemplate = notificationTemplate.replace("${tmdburl}", tmdbUrl);

        String emoji = notificationStatusEnum.getEmoji();
        String action = notificationStatusEnum.getAction();

        notificationTemplate = notificationTemplate.replace("${emoji}", emoji);
        notificationTemplate = notificationTemplate.replace("${action}", action);

        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        String downloadPath = downloadService.getDownloadPath(ani);
        // ${downloadPath[1:]} ${downloadPath[-1]}: 按 / 分段, 同 Python 的下标和切片
        notificationTemplate = PATH_SLICE.matcher(notificationTemplate).replaceAll(m ->
                Matcher.quoteReplacement(slice(downloadPath, m.group(1), !m.group(2).isEmpty(), m.group(3))));
        notificationTemplate = notificationTemplate.replace("${downloadPath}", downloadPath);

        if (notificationTemplate.contains("${jpTitle}")) {
            String jpTitle = RenameUtil.getJpTitle(ani);
            notificationTemplate = notificationTemplate.replace("${jpTitle}", jpTitle);
        }

        notificationTemplate = RenameUtil.replaceEpisodeTitle(notificationTemplate, episode, ani);

        if (!notificationTemplate.contains("${notification}")) {
            return notificationTemplate.trim();
        }

        Config config = ConfigUtil.CONFIG;
        String template = config.getNotificationTemplate();

        template = replaceNotificationTemplate(ani, template, text, notificationStatusEnum);

        notificationTemplate = notificationTemplate.replace("${notification}", template);

        return notificationTemplate.trim();
    }
}
