package com.ai.repository;

import com.ai.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    java.util.Optional<Employee> findFirstByName(String name);
}
