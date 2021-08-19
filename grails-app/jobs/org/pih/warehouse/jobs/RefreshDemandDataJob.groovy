package org.pih.warehouse.jobs

import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext

@DisallowConcurrentExecution
class RefreshDemandDataJob {

    def grailsApplication
    def reportService

    static triggers = {
        cron name: 'refreshDemandDataJobCronTrigger',
                cronExpression: ConfigurationHolder.config.openboxes.jobs.refreshDemandDataJob.cronExpression
    }

    def execute(JobExecutionContext context) {
        Boolean enabled = grailsApplication.config.openboxes.jobs.refreshDemandDataJob.enabled
        if (enabled) {
            def startTime = System.currentTimeMillis()
            log.info("Refreshing demand data: " + context.mergedJobDataMap)
            reportService.refreshProductDemandData()
            log.info "Finished refreshing demand data in " + (System.currentTimeMillis() - startTime) + " ms"
        }
    }
}
