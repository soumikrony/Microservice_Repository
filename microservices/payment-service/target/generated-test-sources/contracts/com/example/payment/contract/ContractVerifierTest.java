package com.example.payment.contract;

import com.example.payment.contract.PaymentContractBase;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import io.restassured.response.ResponseOptions;

import static org.springframework.cloud.contract.verifier.assertion.SpringCloudContractAssertions.assertThat;
import static org.springframework.cloud.contract.verifier.util.ContractVerifierUtil.*;
import static com.toomuchcoding.jsonassert.JsonAssertion.assertThatJson;
import static io.restassured.module.mockmvc.RestAssuredMockMvc.*;

@SuppressWarnings("rawtypes")
public class ContractVerifierTest extends PaymentContractBase {

	@Test
	public void validate_charge_success() throws Exception {
		// given:
			MockMvcRequestSpecification request = given()
					.header("Content-Type", "application/json")
					.body("{\"orderId\":\"ORD-CONTRACT-001\",\"userId\":\"alice\",\"amount\":99.5,\"paymentMethod\":\"CARD\"}");

		// when:
			ResponseOptions response = given().spec(request)
					.post("/api/payments/charge");

		// then:
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.header("Content-Type")).matches("application/json.*");

		// and:
			DocumentContext parsedJson = JsonPath.parse(response.getBody().asString());
			assertThatJson(parsedJson).field("['orderId']").isEqualTo("ORD-CONTRACT-001");
			assertThatJson(parsedJson).field("['amount']").isEqualTo(99.5);

		// and:
			assertThat(parsedJson.read("$.status", String.class)).matches("APPROVED|DECLINED");
			assertThat(parsedJson.read("$.transactionId", String.class)).matches("TXN-.*");
	}

}
