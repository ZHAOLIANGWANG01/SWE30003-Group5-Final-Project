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

  @GetMapping("/workspace/requests")
  public String requests(Model model) {
    access.requireRole(
        edu.delivery.contract.DeliveryData.Role.CUSTOMER,
        edu.delivery.contract.DeliveryData.Role.OFFICE_STAFF);
    model.addAttribute("moduleName", "Requests and quotations");
    model.addAttribute("owner", "Member B");
    return "workspace";
  }

  @GetMapping("/workspace/operations")
  public String operations(Model model) {
    access.requireRole(
        edu.delivery.contract.DeliveryData.Role.OFFICE_STAFF,
        edu.delivery.contract.DeliveryData.Role.DELIVERY_STAFF);
    model.addAttribute("moduleName", "Scheduling and delivery operations");
    model.addAttribute("owner", "Member C");
    return "workspace";
  }
}
