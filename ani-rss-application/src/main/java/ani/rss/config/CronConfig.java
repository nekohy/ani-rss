package ani.rss.config;

import ani.rss.entity.About;
import ani.rss.entity.Config;
import ani.rss.service.BackupService;
import ani.rss.service.UpdateService;
import ani.rss.util.other.ConfigUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CronConfig {

    private static final Config CONFIG = ConfigUtil.CONFIG;

    @Resource
    private BackupService backupService;

    @Resource
    private UpdateService updateService;

    @Scheduled(cron = "0 0 0 * * *")
    public void backupConfig() {
        backupService.backup();
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void autoUpdate() {
        Boolean autoUpdate = CONFIG.getAutoUpdate();
        if (!autoUpdate) {
            // 未开启 自动更新
            return;
        }
        log.info("定时任务 自动更新");
        try {
            About about = updateService.about();
            Boolean update = about.getUpdate();
            autoUpdate = about.getAutoUpdate();
            if (!autoUpdate) {
                // 禁止非跨小版本的自动更新
                return;
            }
            if (update) {
                log.info("检测到可更新版本 v{}", about.getLatest());
            }
            updateService.update(about);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
}
