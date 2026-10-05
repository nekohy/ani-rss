package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.download.OpenList;
import ani.rss.entity.OpenListTaskInfo;
import ani.rss.entity.web.Result;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class OpenListTaskController extends BaseController {
    private final OpenList openList;

    @Auth
    @Operation(summary = "OpenList 任务列表")
    @PostMapping("/openListTasks")
    public Result<List<OpenListTaskInfo>> openListTasks() {
        return Result.success(openList.tasks());
    }
}
