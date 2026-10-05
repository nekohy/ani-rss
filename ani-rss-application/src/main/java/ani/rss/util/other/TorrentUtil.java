package ani.rss.util.other;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 管理下载器的调用与种子存取
 */
@Slf4j
public class TorrentUtil {
    private static final Config CONFIG = ConfigUtil.CONFIG;

    /**
     * 获取种子存放文件夹
     *
     * @param ani 订阅
     * @return 文件夹
     */
    public static File getTorrentDir(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();

        File configDir = ConfigUtil.getConfigDir();

        String s = PinyinUtils.getPinyinInitialLetters(title);

        File torrents = new File(StrFormatter.format("{}/torrents/{}/Season {}", configDir, title, season));
        if (!torrents.exists()) {
            torrents = new File(StrFormatter.format("{}/torrents/{}/{}/Season {}", configDir, s, title, season));
        }
        if (ova) {
            torrents = new File(StrFormatter.format("{}/torrents/{}", configDir, title));
            if (!torrents.exists()) {
                torrents = new File(StrFormatter.format("{}/torrents/{}/{}", configDir, s, title));
            }
        }
        return torrents;
    }

    /**
     * 获取种子
     *
     * @param ani  订阅
     * @param item 资源项
     * @return 种子文件
     */
    public static File getTorrent(Ani ani, Item item) {
        String infoHash = item.getInfoHash();
        File torrents = getTorrentDir(ani);
        String torrent = item.getTorrent();

        File txtFile = new File(torrents, infoHash + ".txt");
        File torrentFile = new File(torrents, infoHash + ".torrent");

        if (txtFile.exists()) {
            return txtFile;
        }
        if (torrentFile.exists()) {
            return torrentFile;
        }

        if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
            return txtFile;
        }
        return torrentFile;
    }

    /**
     * 下载种子文件
     *
     * @param ani  订阅
     * @param item 资源项
     * @return 种子文件
     */
    public static File saveTorrent(Ani ani, Item item) {
        String torrent = item.getTorrent();
        String reName = item.getReName();

        log.info("下载种子 {}", reName);
        File saveTorrentFile = getTorrent(ani, item);
        if (saveTorrentFile.exists()) {
            return saveTorrentFile;
        }

        try {
            if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                log.info("种子下载完成 {}", reName);
                return saveTorrentFile;
            }

            if (ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                log.info("种子下载完成 {}", reName);
                return saveTorrentFile;
            }

            return HttpReq.get(torrent)
                    .thenFunction(res -> {
                        int status = res.getStatus();
                        if (status == 404) {
                            // 如果为 404 则写入空文件 已在 getMagnet 处理过
                            FileUtil.writeUtf8String("", saveTorrentFile);
                            log.info("种子下载完成 {}", reName);
                            return saveTorrentFile;
                        }
                        HttpReq.assertStatus(res);
                        FileUtil.writeFromStream(res.bodyStream(), saveTorrentFile, true);
                        log.info("种子下载完成 {}", reName);
                        return saveTorrentFile;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("下载种子时出现问题 {}", message);
            log.error(message, e);
            // 种子未下载异常，删除
            FileUtil.del(saveTorrentFile);
        }
        return saveTorrentFile;
    }

    /**
     * 登录 OpenList
     *
     * @return 是否登录成功
     */
    public static Boolean login() {
        ThreadUtil.sleep(1000);
        String downloadPath = CONFIG.getDownloadPathTemplate();
        if (StrUtil.isBlank(downloadPath)) {
            log.warn("下载位置未设置");
            return false;
        }
        try {
            return SpringUtil.getBean(OpenList.class).login(false, CONFIG);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 下载
     *
     * @param ani         订阅
     * @param item        下载项
     * @param savePath    保存位置
     * @param torrentFile 种子文件
     * @return 下载状态
     */
    public static Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        return SpringUtil.getBean(OpenList.class).download(ani, item, savePath, torrentFile);
    }

    /**
     * 通过种子获取到磁力链接
     *
     * @param file 文件
     * @return 磁力链接
     */
    public static String getMagnet(File file) {
        String hexHash = FileUtil.mainName(file);
        if (file.length() < 1) {
            return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
        }
        String extName = FileUtil.extName(file);
        if ("txt".equals(extName)) {
            return FileUtil.readUtf8String(file);
        }
        try {
            return TorrentMetadata.from(file).getMagnetUri();
        } catch (Exception e) {
            log.error("转换种子为磁力链接时出现错误 {}", file);
            log.error(e.getMessage(), e);
        }
        return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
    }
}
