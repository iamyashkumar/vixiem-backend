package com.velorix.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest
class BackendApplicationTests {

	@Autowired
	private com.velorix.backend.repository.UserRepository userRepository;

	@Test
	void contextLoads() {
	}

}
