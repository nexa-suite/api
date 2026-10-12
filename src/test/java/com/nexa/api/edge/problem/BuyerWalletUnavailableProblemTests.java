package com.nexa.api.edge.problem;

import com.nexa.api.payments.application.publicapi.BuyerWalletStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerWalletUnavailableProblemTests {

    @Test
    void unavailableWalletCapabilityMapsToServiceUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/buyer/wallet");

        var response = new GlobalExceptionHandler().handleBuyerWalletStoreUnavailable(
                new BuyerWalletStoreUnavailableException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
