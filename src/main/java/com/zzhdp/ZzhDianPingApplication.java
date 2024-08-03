package com.zzhdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.zzhdp.mapper")
@SpringBootApplication
public class ZzhDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZzhDianPingApplication.class, args);
    }

}
