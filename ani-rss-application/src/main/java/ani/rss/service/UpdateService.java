package ani.rss.service;

import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.update.BaseUpdate;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateService {

    /**
     * 关于（fork: 已禁用自动更新检测，永远不会替换为上游 jar）
     *
     * @return 关于信息
     */
    public synchronized About about() {
        String version = MavenUtils.getVersion();

        About about = (About) new About()
                .setVersion(version)
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setMarkdownBody("");
        return about;
    }

    /**
     * 更新程序
     *
     * @param about 关于信息
     */
    public synchronized void update(About about) {
        Boolean update = about.getUpdate();
        if (!update) {
            return;
        }

        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();

        Assert.isTrue(currentFile.isFile(), "不支持更新");

        BaseUpdate baseUpdate = BaseUpdate.getInstance();

        File updateFile = baseUpdate.downloadUpdateFile(about);

        ThreadUtil.execute(() -> {
            try {
                baseUpdate.update(updateFile);
            } catch (Exception e) {
                log.error("更新时遇到错误: {}", e.getMessage(), e);
            }
        });
    }
}
