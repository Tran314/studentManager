CREATE TABLE student (
 sno INT PRIMARY KEY,
 sname VARCHAR(20) NOT NULL,
 age INT NOT NULL,
 address VARCHAR(50),
 CONSTRAINT student_age CHECK (age BETWEEN 1 AND 150),
 CONSTRAINT student_sno_positive CHECK (sno > 0)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE account (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 role VARCHAR(16) NOT NULL,
 student_sno INT UNIQUE,
 auth_version INT NOT NULL DEFAULT 0,
 CONSTRAINT account_student FOREIGN KEY (student_sno) REFERENCES student(sno),
 CONSTRAINT account_role CHECK ((role = 'ADMIN' AND student_sno IS NULL) OR (role = 'STUDENT' AND student_sno IS NOT NULL))
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE app_seed (id INT PRIMARY KEY);

