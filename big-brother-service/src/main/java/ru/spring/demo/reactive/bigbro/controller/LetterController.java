package ru.spring.demo.reactive.bigbro.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.spring.demo.reactive.bigbro.services.GuardService;
import ru.spring.demo.reactive.bigbro.services.LetterDecoder;
import ru.spring.demo.reactive.starter.speed.AdjustmentProperties;
import ru.spring.demo.reactive.starter.speed.model.DecodedLetter;
import ru.spring.demo.reactive.starter.speed.model.Letter;
import ru.spring.demo.reactive.starter.speed.services.LetterRequesterService;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/analyse/letter")
public class LetterController {

    private final LetterDecoder           decoder;
    private final LetterRequesterService  letterRequesterService;
    private final BlockingQueue<Runnable> workingQueue;
    private final ThreadPoolExecutor      letterProcessorExecutor;
    private final GuardService            guardService;
    private final Counter                 counter;

    private final AtomicInteger guardRemainingRequest;

    public LetterController(LetterDecoder decoder,
                            LetterRequesterService letterRequesterService,
                            GuardService guardService,
                            MeterRegistry meterRegistry,
                            AdjustmentProperties adjustmentProperties,
                            ThreadPoolExecutor letterProcessorExecutor) {
        this.decoder = decoder;
        this.letterRequesterService = letterRequesterService;
        this.guardService = guardService;
        this.letterProcessorExecutor = letterProcessorExecutor;

        counter = meterRegistry.counter("letter.rps");
        workingQueue = letterProcessorExecutor.getQueue();
        guardRemainingRequest = adjustmentProperties.getRequest();
    }

    @Scheduled(fixedDelay = 100)
    public void init() {
        if(workingQueue.size() == 0 && guardRemainingRequest.get() > 0) {
            letterRequesterService.request(letterProcessorExecutor.getMaximumPoolSize());
        }
    }

    @Async("letterProcessorExecutor")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public void processLetter(@RequestBody Letter letter) {
        DecodedLetter decode = decoder.decode(letter);
        log.info("decode = " + decode);
        if(letterProcessorExecutor.getQueue().size() == 0 && guardRemainingRequest.get() > 0) {
            letterRequesterService.request(letterProcessorExecutor.getMaximumPoolSize());
            guardRemainingRequest.decrementAndGet();
        }

        guardService.send(decode);
    }

}
