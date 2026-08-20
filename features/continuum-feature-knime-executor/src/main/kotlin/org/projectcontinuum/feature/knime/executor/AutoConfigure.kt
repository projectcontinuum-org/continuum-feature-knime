package org.projectcontinuum.feature.knime.executor

import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.projectcontinuum.feature.knime.executor.config.YamlPropertySourceFactory

@Configuration
@ComponentScan
@PropertySource("classpath:continuum-feature-knime-executor.yaml", factory = YamlPropertySourceFactory::class)
class AutoConfigure
