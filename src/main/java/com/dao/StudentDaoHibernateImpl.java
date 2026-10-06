package com.dao;

import com.pojo.PageResult;
import com.pojo.Student;
import org.hibernate.Session;
import org.hibernate.query.Query;

public class StudentDaoHibernateImpl implements StudentDao {

    @Override
    public Student find(Session session, int sno) {
        return session.find(Student.class, sno);
    }

    @Override
    public void add(Session session, Student student) {
        session.persist(student);
    }

    @Override
    public void delete(Session session, Student student) {
        session.remove(student);
    }

    @Override
    public PageResult<Student> search(
            Session session,
            Integer sno,
            String name,
            int requestedPage,
            int pageSize,
            String sort,
            boolean descending) {
        String where = " where 1=1" + (sno == null ? "" : " and s.sno=:sno")
                + (name.isEmpty() ? "" : " and s.sname like :name escape '!'");
        String column = sortColumn(sort);
        String direction = descending ? " desc" : " asc";
        String orderBy = " order by " + column + direction + ("s.sno".equals(column) ? "" : ", s.sno" + direction);

        Query<Long> count = session.createQuery("select count(s) from Student s" + where, Long.class);
        Query<Student> query = session.createQuery("from Student s" + where + orderBy, Student.class);
        bind(count, sno, name);
        bind(query, sno, name);
        long total = count.getSingleResult();
        int pages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, pages));
        return new PageResult<>(
                query.setFirstResult((page - 1) * pageSize)
                        .setMaxResults(pageSize)
                        .getResultList(),
                page,
                pageSize,
                total,
                pages);
    }

    private String sortColumn(String sort) {
        return switch (sort == null ? "" : sort) {
            case "name" -> "s.sname";
            case "age" -> "s.age";
            default -> "s.sno";
        };
    }

    private void bind(Query<?> query, Integer sno, String name) {
        if (sno != null) {
            query.setParameter("sno", sno);
        }
        if (name.isEmpty()) {
            return;
        }
        // Escape '!' first so the escape character itself is doubled, otherwise
        // the user-supplied wildcards below would not be honoured.
        String escaped = name.replace("!", "!!");
        boolean hasWildcard = name.indexOf('%') >= 0 || name.indexOf('_') >= 0;
        String pattern;
        if (!hasWildcard && isAsciiLettersOrDigits(name)) {
            // Pure ASCII keyword -> prefix match uses idx_student_sname (O(log n)
            // B-tree range scan). Western names like "luc" still find "lucy2".
            pattern = escaped + "%";
        } else {
            // Chinese / mixed / explicit-wildcard -> substring match. The
            // index can't help with leading-% patterns anyway, and users
            // expect "三" to find "张三" rather than only names that start
            // with the character.
            pattern = "%" + escaped.replace("%", "!%").replace("_", "!_") + "%";
        }
        query.setParameter("name", pattern);
    }

    private static boolean isAsciiLettersOrDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }
}
