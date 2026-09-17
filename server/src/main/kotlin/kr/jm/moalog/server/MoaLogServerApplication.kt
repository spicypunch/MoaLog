package kr.jm.moalog.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MoaLogServerApplication

fun main(args: Array<String>) {
    runApplication<MoaLogServerApplication>(*args)
}
