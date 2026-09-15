package com.mopl.batch.content.job;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mopl.batch.content.tasklet.SportsDbContentCollectionTasklet;
import com.mopl.batch.content.tasklet.TmdbContentCollectionTasklet;

@Configuration
public class ContentCollectionJobConfig {

    private static final String JOB_NAME = "contentCollectionJob";
    private static final String TMDB_STEP_NAME = "tmdbContentCollectionStep";
    private static final String SPORTS_DB_STEP_NAME = "sportsDbContentCollectionStep";

    @Bean
    public JobParametersValidator contentCollectionJobParametersValidator() {
        return parameters -> {
            String collectionDate = parameters.getString("collectionDate");

            if (collectionDate == null || collectionDate.isBlank()) {
                throw new JobParametersInvalidException(
                        "collectionDate는 필수 Job Parameter입니다."
                );
            }

            try {
                LocalDate.parse(collectionDate);
            } catch (DateTimeParseException e) {
                throw new JobParametersInvalidException(
                        "collectionDate는 yyyy-MM-dd 형식이어야 합니다."
                );
            }
        };
    }

    @Bean
    public Job contentCollectionJob(
            JobRepository jobRepository,
            @Qualifier(TMDB_STEP_NAME) Step tmdbContentCollectionStep,
            @Qualifier(SPORTS_DB_STEP_NAME) Step sportsDbContentCollectionStep,
            JobParametersValidator contentCollectionJobParametersValidator
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .validator(contentCollectionJobParametersValidator)
                .start(tmdbContentCollectionStep)
                .next(sportsDbContentCollectionStep)
                .build();

    }

    @Bean
    public Step tmdbContentCollectionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TmdbContentCollectionTasklet tasklet
    ) {
        return new StepBuilder(TMDB_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step sportsDbContentCollectionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            SportsDbContentCollectionTasklet tasklet
    ) {
        return new StepBuilder(SPORTS_DB_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
