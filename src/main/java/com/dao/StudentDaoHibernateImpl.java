package com.dao;

import com.pojo.*;
import org.hibernate.Session;
import org.hibernate.query.Query;

public class StudentDaoHibernateImpl implements StudentDao {
    public Student find(Session session, int sno) { return session.find(Student.class, sno); }
    public void add(Session session, Student student) { session.persist(student); }
    public void delete(Session session, Student student) { session.remove(student); }
    public PageResult<Student> search(Session session, Integer sno, String name, int requestedPage) {
        String where = " where 1=1" + (sno == null ? "" : " and s.sno=:sno")
            + (name.isEmpty() ? "" : " and s.sname like :name escape '!'");
        Query<Long> count = session.createQuery("select count(s) from Student s" + where, Long.class);
        Query<Student> query = session.createQuery("from Student s" + where + " order by s.sno", Student.class);
        bind(count, sno, name); bind(query, sno, name);
        long total = count.getSingleResult();
        int pages = (int) Math.max(1, (total + 9) / 10);
        int page = Math.max(1, Math.min(requestedPage, pages));
        return new PageResult<>(query.setFirstResult((page - 1) * 10).setMaxResults(10).getResultList(),
            page, 10, total, pages);
    }
    private void bind(Query<?> query, Integer sno, String name) {
        if (sno != null) query.setParameter("sno", sno);
        if (!name.isEmpty()) query.setParameter("name", "%" + name.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
}

