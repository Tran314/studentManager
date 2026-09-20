package com.service;

import com.pojo.*;

public interface StudentService {
    PageResult<Student> search(Integer sno, String name, int page, int pageSize, String sort, boolean descending);
    Student find(int sno);
    void register(int sno, String name, String password, int age, String address, Identity actor);
    void update(int sno, String name, int age, String address, Identity actor);
    void delete(int sno, Identity actor);
    Identity login(String username, String password);
    Identity current(Identity previous);
    void changePassword(Identity identity, String oldPassword, String newPassword);
    void resetPassword(Identity admin, int sno, String newPassword);
    boolean healthy();
}
