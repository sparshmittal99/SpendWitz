package com.spendsense.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure parsing logic: no Spring, no database, no model. */
class StatementParsingTest {

    @Test
    void parsesIndianAmountFormats() {
        assertThat(Amounts.parse("1,23,456.50").value()).isEqualByComparingTo("123456.50");
        assertThat(Amounts.parse("-350").value()).isEqualByComparingTo("-350");
        assertThat(Amounts.parse("(350.00)").value()).isEqualByComparingTo("-350");
        assertThat(Amounts.parse("Rs. 1,200").value()).isEqualByComparingTo("1200");
        var credit = Amounts.parse("12,450.00 Cr");
        assertThat(credit.value()).isEqualByComparingTo("12450");
        assertThat(credit.creditMarker()).isTrue();
        assertThat(Amounts.parse("1234.50DR").debitMarker()).isTrue();
        assertThat(Amounts.parse("")).isNull();
        assertThat(Amounts.parse("N/A")).isNull();
    }

    @Test
    void checksThatAnAmountIsReallyPrinted() {
        String text = "19/10/26 UPI/DR/6299/GUPTA 350.00 1,41,650.75\n20/10/26 IRCTC 1,245.00 1,40,405.75";
        assertThat(Amounts.appearsIn(text, new BigDecimal("350"))).isTrue();
        assertThat(Amounts.appearsIn(text, new BigDecimal("1245.00"))).isTrue();
        assertThat(Amounts.appearsIn(text, new BigDecimal("35"))).isFalse();
        assertThat(Amounts.appearsIn(text, new BigDecimal("245"))).isFalse();
        assertThat(Amounts.appearsIn(text, new BigDecimal("350.50"))).isFalse();
    }

    @Test
    void parsesBankDateFormats() {
        LocalDate expected = LocalDate.of(2026, 10, 5);
        assertThat(StatementDates.parse("05/10/26", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("05-10-2026", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("05-Oct-2026", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("05 OCT 26", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("2026-10-05", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("05/10/2026 14:32:10", null)).isEqualTo(expected);
        assertThat(StatementDates.parse("10/05/2026", "MM/dd/yyyy")).isEqualTo(expected);
        assertThat(StatementDates.parse("Opening Balance", null)).isNull();
    }

    @Test
    void extractsDebitsFromASavingsAccountTable() {
        List<List<String>> rows = List.of(
                List.of("Demo Bank"),
                List.of("Date", "Narration", "Ref", "Withdrawal Amt.", "Deposit Amt.", "Balance"),
                List.of("", "OPENING BALANCE", "", "", "", "10,000.00"),
                List.of("01/10/26", "SALARY CREDIT", "S1", "", "50,000.00", "60,000.00"),
                List.of("02/10/26", "UPI/DR/630100112233/ZEPTO/zepto@ybl", "630100112233", "642.00", "", "59,358.00"),
                List.of("03/10/26", "ATM WDL/MG ROAD", "A1", "2,000.00", "", "57,358.00"),
                List.of("", "Page 1 of 1"));
        var mapping = new TableExtractor.ColumnMapping(1, 0, 1, 3, 4, null, null, "DEBIT_COLUMN", null, "dd/MM/yy");

        var result = TableExtractor.extract(rows, mapping);

        assertThat(result.debits()).extracting(StatementLine::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("642"), new BigDecimal("2000"));
        assertThat(result.debits().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(result.credits()).isEqualTo(1);
    }

    @Test
    void extractsPurchasesFromACreditCardTable() {
        List<List<String>> rows = List.of(
                List.of("Date", "Transaction Details", "Amount (Rs.)"),
                List.of("05-Oct-2026", "AMAZON PAY INDIA", "1,299.00"),
                List.of("07-Oct-2026", "PAYMENT RECEIVED - THANK YOU", "12,450.00 Cr"),
                List.of("08-Oct-2026", "REFUND FLIPKART", "-499.00"));
        var mapping = new TableExtractor.ColumnMapping(0, 0, 1, null, null, 2, null, "POSITIVE_AMOUNT", null, null);

        var result = TableExtractor.extract(rows, mapping);

        assertThat(result.debits()).singleElement().satisfies(line -> {
            assertThat(line.description()).isEqualTo("AMAZON PAY INDIA");
            assertThat(line.amount()).isEqualByComparingTo("1299");
        });
        assertThat(result.credits()).isEqualTo(2);
    }

    @Test
    void rejectsAMappingWithoutTheNeededColumns() {
        var mapping = new TableExtractor.ColumnMapping(0, 0, null, null, null, 2, null, "POSITIVE_AMOUNT", null, null);
        assertThatThrownBy(() -> TableExtractor.extract(List.of(List.of("a")), mapping))
                .isInstanceOf(StatementException.class);
    }
}
