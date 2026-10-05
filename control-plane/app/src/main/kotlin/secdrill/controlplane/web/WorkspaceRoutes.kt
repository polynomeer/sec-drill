package secdrill.controlplane.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Client-side routes of the learner workspace (web/): deep links such as `/app/sessions/{id}` load the same page.
 * Asset requests (with a file extension) are served as static files and never reach this controller.
 */
@Controller
class WorkspaceRoutes {
    @GetMapping("/app", "/app/", "/app/scenarios", "/app/scenarios/{id}", "/app/sessions/{id}")
    fun page(): String = "forward:/app/index.html"
}
