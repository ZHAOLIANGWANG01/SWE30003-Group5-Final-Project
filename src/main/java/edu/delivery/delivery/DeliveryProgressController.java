package edu.delivery.delivery;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class DeliveryProgressController {
  private final DeliveryService deliveries;

  public DeliveryProgressController(DeliveryService deliveries) {
    this.deliveries = deliveries;
  }

  @GetMapping("/tracking/{requestId}/events")
  public String events(@PathVariable long requestId, Model model) {
    model.addAttribute("requestId", requestId);
    model.addAttribute("events", deliveries.customerProgress(requestId));
    model.addAttribute("schedule", deliveries.scheduleForCustomer(requestId));
    return "delivery/tracking";
  }
}
