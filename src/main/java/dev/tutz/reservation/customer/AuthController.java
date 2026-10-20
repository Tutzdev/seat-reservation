package dev.tutz.reservation.customer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final CustomerService customers;

    public AuthController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse register(@Valid @RequestBody RegisterRequest request) {
        return customers.register(request);
    }

    @PostMapping("/token")
    public TokenResponse issueToken(@Valid @RequestBody TokenRequest request) {
        return customers.issueToken(request);
    }
}
