package co.com.ticketing.model.common;

import co.com.ticketing.model.common.ex.BusinessError;
import co.com.ticketing.model.common.ex.BusinessException;
import co.com.ticketing.model.common.ex.TechnicalError;
import co.com.ticketing.model.common.ex.TechnicalException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionsTest {

    @Test
    void businessExceptionCarriesItsError() {
        var error = new BusinessException(BusinessError.EVENT_NOT_FOUND);

        assertThat(error.getMessage()).isEqualTo(BusinessError.EVENT_NOT_FOUND.getMessage());
        assertThat(error.is(BusinessError.EVENT_NOT_FOUND)).isTrue();
        assertThat(error.is(BusinessError.ORDER_NOT_FOUND)).isFalse();
        assertThat(error.getError().getCategory()).isEqualTo(BusinessError.Category.NOT_FOUND);
    }

    @Test
    void technicalExceptionExposesRetryability() {
        var cause = new IllegalStateException("boom");
        var retryable = new TechnicalException(TechnicalError.CONCURRENT_UPDATE, cause);
        var permanent = new TechnicalException(TechnicalError.INVALID_MESSAGE, cause);

        assertThat(retryable.isRetryable()).isTrue();
        assertThat(retryable.getCause()).isSameAs(cause);
        assertThat(retryable.getError()).isEqualTo(TechnicalError.CONCURRENT_UPDATE);
        assertThat(permanent.isRetryable()).isFalse();
        assertThat(TechnicalException.isRetryable(retryable)).isTrue();
        assertThat(TechnicalException.isRetryable(permanent)).isFalse();
        assertThat(TechnicalException.isRetryable(new BusinessException(BusinessError.INVALID_REQUEST))).isFalse();
    }
}
