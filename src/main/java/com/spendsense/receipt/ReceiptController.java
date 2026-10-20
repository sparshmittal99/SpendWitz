package com.spendsense.receipt;

import java.io.IOException;
import java.util.Base64;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendsense.common.AiUnavailableException;
import com.spendsense.common.QuotaExceededException;
import com.spendsense.user.AuthUser;

@Controller
@RequestMapping("/receipts")
public class ReceiptController {

    private static final String SAMPLE_RECEIPT = "static/img/sample-receipt.png";
    private static final String SAMPLE_UPI = "static/img/sample-upi.png";

    private final ReceiptScanService scanner;

    public ReceiptController(ReceiptScanService scanner) {
        this.scanner = scanner;
    }

    @GetMapping("/scan")
    public String page() {
        return "receipts/scan";
    }

    @PostMapping("/scan")
    public String scan(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file, Model model,
            RedirectAttributes redirect) throws IOException {
        if (file.isEmpty() || file.getContentType() == null) {
            redirect.addFlashAttribute("error", "Choose a receipt photo or PDF first.");
            return "redirect:/receipts/scan";
        }
        return review(user, file.getBytes(), file.getContentType(), model, redirect);
    }

    @PostMapping("/sample")
    public String scanSample(@AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "receipt") String kind, Model model, RedirectAttributes redirect)
            throws IOException {
        byte[] bytes = new ClassPathResource("upi".equals(kind) ? SAMPLE_UPI : SAMPLE_RECEIPT).getContentAsByteArray();
        return review(user, bytes, "image/png", model, redirect);
    }

    private String review(AuthUser user, byte[] bytes, String contentType, Model model, RedirectAttributes redirect) {
        try {
            var result = scanner.scan(user, bytes, contentType);
            model.addAttribute("form", result.form());
            model.addAttribute("categoryReason", result.categoryReason());
            if (contentType.startsWith("image/") && !contentType.contains("hei")) {
                model.addAttribute("receiptPreview",
                        "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes));
            }
            return "expenses/form";
        } catch (QuotaExceededException | AiUnavailableException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/receipts/scan";
        }
    }
}
