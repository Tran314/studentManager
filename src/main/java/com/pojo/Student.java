package com.pojo;

import jakarta.persistence.*;

@Entity
@Table(name = "student")
public class Student {
    @Id
    private Integer sno;

    @Column(nullable = false, length = 20)
    private String sname;

    @Column(nullable = false)
    private Integer age;

    @Column(length = 50)
    private String address;

    public Student() {}

    public Student(Integer sno, String sname, Integer age, String address) {
        this.sno = sno;
        this.sname = sname;
        this.age = age;
        this.address = address;
    }

    public Integer getSno() {
        return sno;
    }

    public String getSname() {
        return sname;
    }

    public Integer getAge() {
        return age;
    }

    public String getAddress() {
        return address;
    }

    public void update(String name, int age, String address) {
        this.sname = name;
        this.age = age;
        this.address = address;
    }
}
