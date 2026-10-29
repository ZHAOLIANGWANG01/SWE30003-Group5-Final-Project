package edu.delivery.web;

import edu.delivery.auth.AccessGuard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
  private final AccessGuard access;

  public HomeController(AccessGuard access) {
    this.access = access;
  }

  @GetMapping("/login")
  public String login() {
    return "auth/login";
  }

  @GetMapping("/")
  public String home(Model model) {
    model.addAttribute("person", access.current());
    return "home";
  }

  @GetMapping({"/portal/customer", "/portal/office", "/portal/driver"})
  public String portal(Model model) {
    return home(model);
  }
}
