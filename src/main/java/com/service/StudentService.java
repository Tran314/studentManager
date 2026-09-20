package com.service;

import com.pojo.*;

public interface StudentService {
    PageResult<Student> search(Integer sno, String name, int page);
    Student find(int sno);
    void register(int sno, String name, String password, int age, String address);
    void update(int sno, String name, int age, String address);
    void delete(int sno);
    Identity login(String username, String password);
    Identity current(Identity previous);
    void changePassword(Identity identity, String oldPassword, String newPassword);
    boolean healthy();
}

