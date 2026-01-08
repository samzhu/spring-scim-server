package io.github.samzhu.scim;

import org.springframework.boot.SpringApplication;

public class TestSpringScimServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpringScimServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
