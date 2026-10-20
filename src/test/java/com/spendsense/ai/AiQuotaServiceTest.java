package com.spendsense.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.spendsense.IntegrationTest;
import com.spendsense.common.QuotaExceededException;

class AiQuotaServiceTest extends IntegrationTest {

    @Autowired
    AiQuotaService quota;

    @Test
    void blocksAfterTheDailyLimit() {
        var user = newUser(); // the test profile allows 3 calls per day

        quota.consume(user);
        quota.consume(user);
        quota.consume(user);

        assertThat(quota.remainingToday(user)).isZero();
        assertThatThrownBy(() -> quota.consume(user)).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void limitsAreCountedPerUser() {
        var first = newUser();
        var second = newUser();

        quota.consume(first);
        quota.consume(first);

        assertThat(quota.remainingToday(first)).isEqualTo(1);
        assertThat(quota.remainingToday(second)).isEqualTo(3);
    }
}
