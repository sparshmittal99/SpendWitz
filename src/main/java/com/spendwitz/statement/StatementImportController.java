package com.spendwitz.statement;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.spendwitz.common.AiUnavailableException;
import com.spendwitz.user.AuthUser;

@Controller
@RequestMapping("/import")
public class StatementImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final int MAX_FILES = 4;

    private final StatementImportService importer;
    private final SampleStatementGenerator samples;

    public StatementImportController(StatementImportService importer, SampleStatementGenerator samples) {
        this.importer = importer;
        this.samples = samples;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("samplePassword", SampleStatementGenerator.SAMPLE_PDF_PASSWORD);
        return "statement/index";
    }

    @PostMapping
    public String upload(@AuthenticationPrincipal AuthUser user, @RequestParam("file") List<MultipartFile> files,
            @RequestParam(required = false) String password, Model model, RedirectAttributes redirect)
            throws IOException {
        List<MultipartFile> chosen = files.stream().filter(f -> !f.isEmpty()).toList();
        if (chosen.isEmpty()) {
            redirect.addFlashAttribute("error", "Choose a statement file first.");
            return "redirect:/import";
        }
        if (chosen.size() > MAX_FILES) {
            redirect.addFlashAttribute("error", "Choose up to " + MAX_FILES + " statements at a time.");
            return "redirect:/import";
        }
        List<StatementImportService.Upload> uploads = new ArrayList<>();
        for (MultipartFile f : chosen) {
            uploads.add(new StatementImportService.Upload(f.getOriginalFilename(), f.getBytes()));
        }
        return preview(user, uploads, password, model, redirect);
    }

    @PostMapping("/sample")
    public String sample(@AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "xlsx") String format,
            Model model, RedirectAttributes redirect) {
        boolean pdf = "pdf".equals(format);
        byte[] bytes = pdf ? samples.pdf(user) : samples.excel(user);
        var upload = new StatementImportService.Upload(pdf ? "sample-statement.pdf" : "sample-statement.xlsx", bytes);
        return preview(user, List.of(upload), pdf ? SampleStatementGenerator.SAMPLE_PDF_PASSWORD : null, model,
                redirect);
    }

    /** Lets visitors see exactly what the sample file looks like. */
    @GetMapping("/sample-statement.{format}")
    public ResponseEntity<byte[]> downloadSample(@AuthenticationPrincipal AuthUser user,
            @PathVariable String format) {
        boolean pdf = "pdf".equals(format);
        if (!pdf && !"xlsx".equals(format)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("sample-statement." + format).build().toString())
                .body(pdf ? samples.pdf(user) : samples.excel(user));
    }

    private String preview(AuthUser user, List<StatementImportService.Upload> uploads, String password, Model model,
            RedirectAttributes redirect) {
        try {
            var batch = importer.previewAll(user, uploads, password);
            StatementImportForm form = new StatementImportForm();
            form.setRows(new ArrayList<>(batch.preview().rows()));
            model.addAttribute("form", form);
            model.addAttribute("preview", batch.preview());
            model.addAttribute("fileNames", uploads.stream().map(StatementImportService.Upload::fileName).toList());
            model.addAttribute("failures", batch.failures());
            return "statement/review";
        } catch (StatementException | AiUnavailableException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/import";
        }
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal AuthUser user, @ModelAttribute("form") StatementImportForm form,
            Model model, RedirectAttributes redirect) {
        List<String> errors = new ArrayList<>();
        int included = 0;
        for (int i = 0; i < form.getRows().size(); i++) {
            var row = form.getRows().get(i);
            if (!row.isInclude()) {
                continue;
            }
            included++;
            String label = "Row " + (i + 1);
            if (row.getMerchant() == null || row.getMerchant().isBlank() || row.getMerchant().length() > 150) {
                errors.add(label + ": enter a merchant (up to 150 characters).");
            }
            if (row.getAmount() == null || row.getAmount().signum() <= 0
                    || row.getAmount().compareTo(new BigDecimal("9999999999")) > 0) {
                errors.add(label + ": enter an amount greater than 0.");
            }
            if (row.getExpenseDate() == null) {
                errors.add(label + ": enter a date.");
            }
            if (!row.isAtmWithdrawal() && (row.getCategory() == null || row.getPaymentMethod() == null)) {
                errors.add(label + ": choose a category and payment method.");
            }
            if (row.getNote() != null && row.getNote().length() > 500) {
                row.setNote(row.getNote().substring(0, 500));
            }
        }
        if (included == 0 && errors.isEmpty()) {
            errors.add("Tick at least one transaction to import.");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            return "statement/review";
        }
        var result = importer.save(user, form.getRows());
        String message = "Imported " + result.expenses() + (result.expenses() == 1 ? " expense" : " expenses");
        if (result.withdrawals() > 0) {
            message += " and added " + result.withdrawals() + " ATM withdrawal"
                    + (result.withdrawals() == 1 ? "" : "s") + " to your cash wallet";
        }
        redirect.addFlashAttribute("success", message + ".");
        return "redirect:/expenses";
    }
}
