import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "charge payment successfully"
    request {
        method POST()
        url "/api/payments/charge"
        headers {
            contentType(applicationJson())
        }
        body(
                orderId: "ORD-CONTRACT-001",
                userId: "alice",
                amount: 99.5,
                paymentMethod: "CARD"
        )
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        bodyMatchers {
            jsonPath('$.status', byRegex('APPROVED|DECLINED'))
            jsonPath('$.transactionId', byRegex('TXN-.*'))
        }
        body(
                status: $(consumer('APPROVED'), producer(regex('APPROVED|DECLINED'))),
                orderId: "ORD-CONTRACT-001",
                amount: 99.5,
                transactionId: $(consumer('TXN-12345'), producer(regex('TXN-.*')))
        )
    }
}
