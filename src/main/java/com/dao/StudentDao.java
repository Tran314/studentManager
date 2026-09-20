package com.dao;

import com.pojo.*;
import org.hibernate.Session;

public interface StudentDao {
    Student find(Session session, int sno);
    PageResult<Student> search(Session session, Integer sno, String name, int page, int pageSize, String sort, boolean descending);
    void add(Session session, Student student);
    void delete(Session session, Student student);
}
