package com.spendwitz.receipt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

import com.spendwitz.common.AiUnavailableException;
import com.spendwitz.common.PaymentMethod;
import com.spendwitz.common.QuotaExceededException;
import com.spendwitz.quickadd.QuickAddForm;
import com.spendwitz.user.AuthUser;

@Controller
@RequestMapping("/receipts")
public class ReceiptController {

    private static final String SAMPLE_RECEIPT = "static/img/sample-receipt.png";
    private static final String SAMPLE_UPI = "static/img/sample-upi.png";
    private static final int MAX_FILES = 8;

    private final ReceiptScanService scanner;

    public ReceiptController(ReceiptScanService scanner) {
        this.scanner = scanner;
    }

    @GetMapping("/scan")
    public String page() {
        return "receipts/scan";
    }

    @PostMapping("/scan")
    public String scan(@AuthenticationPrincipal AuthUser user, @RequestParam("file") List<MultipartFile> files,
            Model model, RedirectAttributes redirect) throws IOException {
        List<MultipartFile> chosen = files.stream().filter(f -> !f.isEmpty() && f.getContentType() != null).toList();
        if (chosen.isEmpty()) {
            redirect.addFlashAttribute("error", "Choose a receipt photo or PDF first.");
            return "redirect:/receipts/scan";
        }
        if (chosen.size() > MAX_FILES) {
            redirect.addFlashAttribute("error", "Choose up to " + MAX_FILES + " bills at a time.");
            return "redirect:/receipts/scan";
        }
        if (chosen.size() == 1) {
            MultipartFile file = chosen.getFirst();
            return review(user, file.getBytes(), file.getContentType(), model, redirect);
        }
        List<ReceiptScanService.Upload> uploads = new ArrayList<>();
        for (MultipartFile f : chosen) {
            uploads.add(new ReceiptScanService.Upload(f.getOriginalFilename(), f.getBytes(), f.getContentType()));
        }
        return reviewAll(user, uploads, model, redirect);
    }

    /** Several bills: one editable row each, saved together from the quick-add review table. */
    private String reviewAll(AuthUser user, List<ReceiptScanService.Upload> uploads, Model model,
            RedirectAttributes redirect) {
        var batch = scanner.scanAll(user, uploads);
        if (batch.results().isEmpty()) {
            redirect.addFlashAttribute("error", "None of the bills could be read. " + String.join(" ", batch.failures()));
            return "redirect:/receipts/scan";
        }
        QuickAddForm form = new QuickAddForm();
        form.setRows(new ArrayList<>(batch.results().stream().map(r -> QuickAddForm.Row.fromScan(r.form())).toList()));
        model.addAttribute("form", form);
        model.addAttribute("failures", batch.failures());
        model.addAttribute("scannedBills", uploads.size());
        model.addAttribute("methods", PaymentMethod.values());
        return "quickadd/review";
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
