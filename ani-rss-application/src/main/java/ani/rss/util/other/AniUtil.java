package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.exception.ResultException;
import ani.rss.handle.JsonReader;
import ani.rss.handle.JsonWriter;
import ani.rss.service.MikanService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class AniUtil {

    private static final Config CONFIG = ConfigUtil.CONFIG;
    public static final List<Ani> ANI_LIST = new CopyOnWriteArrayList<>();
    public static final String FILE_NAME = "ani.v2.json";

    /**
     * 获取订阅配置文件
     *
     * @return 配置文件
     */
    public static File getAniFile() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir + File.separator + FILE_NAME);
    }

    /**
     * 加载订阅
     */
    public static void load() {
        File configFile = getAniFile();

        List<Ani> anis = JsonReader.getInstance(configFile)
                .toList(Ani.class, ANI_LIST);

        CopyOptions copyOptions = CopyOptions
                .create()
                .setIgnoreNullValue(true)
                .setOverride(false);

        ANI_LIST.clear();
        for (Ani ani : anis) {
            Date releaseDate = ani.getReleaseDate();
            if (Objects.isNull(releaseDate)) {
                releaseDate = new Date();
                // 处理旧的日期数据
                try {
                    Integer year = ani.getYear();
                    Integer month = ani.getMonth();
                    Integer date = ani.getDate();
                    String format = StrUtil.format("{}-{}-{}", year, month, date);
                    releaseDate = DateUtil.parse(format, DatePattern.NORM_DATE_PATTERN);
                } catch (Exception ignored) {
                }
                ani.setReleaseDate(releaseDate);
            }

            // 自动修补缺失的封面
            String image = ani.getImage();
            String cover = saveCover(image);
            ani.setCover(cover);

            Ani newAni = AniUtil.createAni();
            BeanUtil.copyProperties(newAni, ani, copyOptions);
            ANI_LIST.add(ani);
        }
        log.debug("加载订阅 共{}项", ANI_LIST.size());
    }

    /**
     * 将订阅配置保存到磁盘
     */
    public static synchronized void sync() {
        File configFile = getAniFile();
        log.debug("保存订阅 {}", configFile);
        try {
            // 写入到硬盘
            JsonWriter.getInstance(configFile)
                    .writer(ANI_LIST);
            log.debug("保存成功 {}", configFile);
        } catch (Exception e) {
            log.error("保存失败 {}", configFile);
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 获取动漫信息
     *
     * @param dto DTO
     * @return 订阅
     */
    public static Ani getAni(RssToAniDTO dto) {
        String url = dto.getUrl();
        String type = dto.getType();
        Boolean enable = dto.getEnable();
        enable = ObjectUtil.defaultIfNull(enable, true);

        Assert.notBlank(url, "RSS地址 不能为空");

        type = StrUtil.blankToDefault(type, "mikan");

        Ani ani = AniUtil.createAni();
        ani.setUrl(url);

        Map<String, String> paramMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        switch (type) {
            case "mikan":
                try {
                    String subgroup = dto.getSubgroup();
                    String bgmUrl = dto.getBgmUrl();
                    if (StrUtil.isAllBlank(subgroup, bgmUrl)) {
                        String subgroupId = MikanService.getSubgroupId(url);
                        MikanService.getMikanInfo(ani, subgroupId);
                    } else {
                        ani.setBgmUrl(bgmUrl)
                                .setSubgroup(subgroup);
                    }
                } catch (Exception e) {
                    throw ResultException.exception("获取失败");
                }
                break;
            case "ani-bt":
                if (paramMap.containsKey("bgmId")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("bgmId");
                    ani.setBgmUrl(bgmUrl);
                }

                String subgroup = dto.getSubgroup();
                if (paramMap.containsKey("groupSlug") && StrUtil.isBlank(subgroup)) {
                    subgroup = paramMap.get("groupSlug");
                }
                ani.setSubgroup(subgroup);
                break;
            case "anime-garden":
                if (paramMap.containsKey("subject")) {
                    String bgmUrl = "https://bgm.tv/subject/" + paramMap.get("subject");
                    ani.setBgmUrl(bgmUrl);
                }
                if (paramMap.containsKey("fansub")) {
                    ani.setSubgroup(paramMap.get("fansub"));
                }
                break;
            default:
                String bgmUrl = dto.getBgmUrl();
                ani.setBgmUrl(bgmUrl);
        }

        String bgmUrl = ani.getBgmUrl();
        String subgroup = ani.getSubgroup();

        Assert.notBlank(bgmUrl, "bgmUrl 不能为空");

        BgmInfo bgmInfo = BgmUtil.getBgmInfo(ani, true);

        BgmUtil.toAni(bgmInfo, ani);

        // 只下载最新集
        Boolean downloadNew = CONFIG.getDownloadNew();
        // 默认启用全局排除
        Boolean enabledExclude = CONFIG.getEnabledExclude();
        // 默认导入全局排除
        Boolean importExclude = CONFIG.getImportExclude();
        // 全局排除
        List<String> exclude = CONFIG.getExclude();

        // 默认导入全局排除
        if (importExclude) {
            exclude = new ArrayList<>(exclude);
            exclude.addAll(ani.getExclude());
            exclude = exclude.stream().distinct().toList();
            ani.setExclude(exclude);
        }

        ani
                // 只下载最新集
                .setDownloadNew(downloadNew)
                // 是否启用全局排除
                .setGlobalExclude(enabledExclude)
                // type mikan or other
                .setType(type)
                .setEnable(enable);

        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        if (subgroup.equals("未知字幕组")) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            subgroup = ItemsUtil.getSubgroup(items);
        }

        ani.setSubgroup(subgroup);

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();

        boolean copyMasterToStandby = CONFIG.getCopyMasterToStandby();
        boolean standbyRss = CONFIG.getStandbyRss();
        if (copyMasterToStandby && standbyRss) {
            StandbyRss copyStandbyRss = new StandbyRss()
                    .setUrl(url.trim())
                    .setOffset(0)
                    .setLabel(ani.getSubgroup());
            standbyRssList.add(copyStandbyRss);
        }

        log.debug("获取到动漫信息 {}", JSONUtil.formatJsonStr(GsonStatic.toJson(ani)));
        if (ani.getOva()) {
            return ani;
        }

        // 自动推断剧集偏移
        if (CONFIG.getOffset()) {
            List<Item> items = ItemsUtil.getItems(ani, url, subgroup);
            if (items.isEmpty()) {
                return ani;
            }
            Double offset = -(items.stream()
                    .map(Item::getEpisode)
                    .min(Comparator.comparingDouble(i -> i))
                    .get() - 1);
            log.debug("自动获取到剧集偏移为 {}", offset);
            ani.setOffset(offset.intValue());

            for (StandbyRss rss : standbyRssList) {
                rss.setOffset(offset.intValue());
            }
        }
        return ani;
    }


    public static String saveCover(String coverUrl) {
        return saveCover(coverUrl, false);
    }

    /**
     * 保存图片
     *
     * @param coverUrl   图片链接
     * @param isOverride 是否覆盖
     * @return 相对位置
     */
    public static String saveCover(String coverUrl, Boolean isOverride) {
        File configDir = ConfigUtil.getConfigDir();
        File filesDir = new File(configDir, "files");
        FileUtil.mkdir(filesDir);

        // 默认空图片
        String cover = "cover.png";
        File defaultFile = new File(filesDir, cover);
        if (!defaultFile.exists()) {
            try (InputStream inputStream = ResourceUtil.getStream("image/cover.png")) {
                FileUtil.writeFromStream(inputStream, defaultFile);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        if (StrUtil.isBlank(coverUrl)) {
            return cover;
        }

        String extName = FileUtil.extName(URLUtil.getPath(coverUrl));
        // 取url的md5作为文件名, 避免重复下载
        String filename = SecureUtil.md5(coverUrl) + "." + extName;

        File dir = new File(filesDir, String.valueOf(filename.charAt(0)));

        FileUtil.mkdir(dir);
        File file = new File(dir, filename);
        if (file.exists() && !isOverride) {
            return filename.charAt(0) + "/" + filename;
        }
        FileUtil.del(file);
        try {
            HttpReq.get(coverUrl)
                    .then(res -> FileUtil.writeFromStream(res.bodyStream(), file));
            return filename.charAt(0) + "/" + filename;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return cover;
        }
    }


    /**
     * 获取蜜柑的bangumiId
     *
     * @param ani 订阅
     * @return bangumiId
     */
    public static String getBangumiId(Ani ani) {
        String url = ani.getUrl();
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);
        return decodeParamMap.get("bangumiId");
    }


    public static Ani createAni() {
        Ani newAni = new Ani();
        return newAni
                .setId(UUID.randomUUID().toString())
                .setMikanTitle("")
                .setStandbyRssList(new ArrayList<>())
                .setOffset(0)
                .setReleaseDate(new DateTime())
                .setEnable(true)
                .setOva(false)
                .setScore(0.0)
                .setLastDownloadTime(0L)
                .setImage("")
                .setThemoviedbName("")
                .setCustomDownloadPath(false)
                .setCustomDownloadPathTemplate("")
                .setGlobalExclude(false)
                .setCurrentEpisodeNumber(0)
                .setTotalEpisodeNumber(0)
                .setMatch(List.of())
                .setExclude(List.of("720[Pp]", "\\d-\\d", "合集", "特别篇"))
                .setBgmUrl("")
                .setSubgroup("")
                .setCustomEpisode(CONFIG.getCustomEpisode())
                .setCustomEpisodeStr(CONFIG.getCustomEpisodeStr())
                .setCustomEpisodeGroupIndex(CONFIG.getCustomEpisodeGroupIndex())
                .setOmit(true)
                .setDownloadNew(false)
                .setNotDownload(new ArrayList<>())
                .setTmdb(
                        new Tmdb()
                                .setId("")
                                .setName("")
                                .setOriginalName("")
                                .setDate(new Date())
                )
                .setUpload(CONFIG.getUpload())
                .setProcrastinating(true)
                .setCustomRenameTemplate(CONFIG.getRenameTemplate())
                .setCustomRenameTemplateEnable(false)
                .setMessage(true)
                .setCustomUploadPathTarget("")
                .setCustomUploadEnable(false);
    }


}
