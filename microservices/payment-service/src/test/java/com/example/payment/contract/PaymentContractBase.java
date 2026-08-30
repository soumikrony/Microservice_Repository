package com.example.payment.contract;

import com.example.payment.PaymentServiceApplication;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PaymentServiceApplication.class)
@AutoConfigureMockMvc
public abstract class PaymentContractBase {

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }
}
