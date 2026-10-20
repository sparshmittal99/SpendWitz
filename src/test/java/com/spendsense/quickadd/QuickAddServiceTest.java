package com.spendsense.quickadd;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.spendsense.IntegrationTest;
import com.spendsense.common.Category;
import com.spendsense.common.PaymentMethod;

/** Checks how model output is cleaned up, without calling the model. */
class QuickAddServiceTest extends IntegrationTest {

    @Autowired
    QuickAddService service;

    @Autowired
    Clock clock;

    @Test
    void cleansUpWhatTheModelReturns() {
        var user = newUser();
        LocalDate today = LocalDate.now(clock);
        var parsed = new QuickAddService.ParsedText(List.of(
                new QuickAddService.ParsedEntry("Sabzi", new BigDecimal("150"), "groceries", today.toString(), null, null),
                new QuickAddService.ParsedEntry("Gupta Store", new BigDecimal("350"), "SHOPPING", today.minusDays(1).toString(), "gpay", "atta"),
                new QuickAddService.ParsedEntry("Bread", null, "GROCERIES", null, null, null),
                new QuickAddService.ParsedEntry("Chai", new BigDecimal("20"), "nonsense", today.plusDays(3).toString(), null, null)),
                List.of("milk"));

        var result = service.toResult(user, parsed, today, PaymentMethod.CASH);

        assertThat(result.drafts()).extracting(QuickAddService.Draft::merchant).containsExactly("Sabzi", "Gupta Store", "Chai");
        assertThat(result.unclear()).containsExactly("milk", "Bread");

        var sabzi = result.drafts().get(0);
        assertThat(sabzi.category()).isEqualTo(Category.GROCERIES);
        assertThat(sabzi.paymentMethod()).isEqualTo(PaymentMethod.CASH); // the user's default

        var store = result.drafts().get(1);
        assertThat(store.paymentMethod()).isEqualTo(PaymentMethod.UPI); // "gpay" in the text wins
        assertThat(store.expenseDate()).isEqualTo(today.minusDays(1));

        var chai = result.drafts().get(2);
        assertThat(chai.category()).isEqualTo(Category.OTHER); // unknown category falls back
        assertThat(chai.expenseDate()).isEqualTo(today); // future dates are clamped
    }

    @Test
    void theUsersPastCategoryForAMerchantBeatsTheModel() {
        var user = newUser();
        LocalDate today = LocalDate.now(clock);
        addExpense(user, "Sharma Kirana Store", "200", Category.GROCERIES, today.minusDays(7));
        var parsed = new QuickAddService.ParsedText(List.of(
                new QuickAddService.ParsedEntry("sharma kirana store", new BigDecimal("320"), "SHOPPING", null, null, null)),
                List.of());

        var result = service.toResult(user, parsed, today, PaymentMethod.CASH);

        assertThat(result.drafts().getFirst().category()).isEqualTo(Category.GROCERIES);
    }
}
