/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamsets.datacollector;

import com.google.common.base.Splitter;
import com.streamsets.datacollector.callback.CallbackInfo;
import com.streamsets.datacollector.execution.Manager;
import com.streamsets.datacollector.execution.PipelineInfo;
import com.streamsets.datacollector.execution.Runner;
import com.streamsets.datacollector.http.ServerNotYetRunningException;
import com.streamsets.datacollector.main.BuildInfo;
import com.streamsets.datacollector.main.LogConfigurator;
import com.streamsets.datacollector.main.MainSlavePipelineManagerModule;
import com.streamsets.datacollector.main.PipelineTask;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.main.ShutdownHandler;
import com.streamsets.datacollector.runner.Pipeline;
import com.streamsets.datacollector.security.SecurityContext;
import com.streamsets.datacollector.task.Task;
import com.streamsets.datacollector.task.TaskWrapper;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.impl.DataCollector;

import dagger.ObjectGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class EmbeddedDataCollector implements DataCollector {
  private static final Logger LOG = LoggerFactory.getLogger(EmbeddedDataCollector.class);
  private String pipelineName;
  private Manager pipelineManager;
  private ObjectGraph dagger;
  private Thread waitingThread;
  private Task task;
  private RuntimeInfo runtimeInfo;
  private Runner runner;
  private PipelineTask pipelineTask;
  private SecurityContext securityContext;


  @Override
  public void startPipeline() throws Exception {
    Subject.doAs(securityContext.getSubject(), new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        File sdcProperties = new File(runtimeInfo.getConfigDir(), "sdc.properties");
        Utils.checkState(sdcProperties.exists(), Utils.format("sdc property file doesn't exist at '{}'",
          sdcProperties.getAbsolutePath()));
        Properties properties = new Properties();

        InputStream is = null;
        try {
          is = new FileInputStream(sdcProperties);
          properties.load(is);
        } finally {
          if (is != null) {
            is.close();
          }
        }
        String masterSDCId = Utils.checkNotNull(properties.getProperty("sdc.id"), "SDC_ID");
        LOG.info(Utils.format("Master sdc id is: '{}'", masterSDCId));
        runtimeInfo.setMasterSDCId(masterSDCId);
        String pipelineName = Utils.checkNotNull(properties.getProperty("cluster.pipeline.name"), "Pipeline name");
        String pipelineUser = Utils.checkNotNull(properties.getProperty("cluster.pipeline.user"), "Pipeline user");
        String pipelineRev = Utils.checkNotNull(properties.getProperty("cluster.pipeline.rev"), "Pipeline revision");
        runner = pipelineManager.getRunner(pipelineUser, pipelineName, pipelineRev);
        runner.start();
        return null;
      }
    });
  }

  @Override
  public void createPipeline(String pipelineJson) throws Exception {
    throw new UnsupportedOperationException("This method is not supported. Use \"startPipeline\" method");

  }

  @Override
  public void stopPipeline() throws Exception {
    throw new UnsupportedOperationException("This method is not supported. Use \"startPipeline\" method");
  }

  @Override
  public void init() throws Exception {
    final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    LOG.info("Entering SDC with ClassLoader: " + Thread.currentThread().getContextClassLoader());
    LOG.info("Java classpath");
    for (String entry : Splitter.on(File.pathSeparator).omitEmptyStrings().
      split(System.getProperty("java.class.path"))) {
      LOG.info(entry);
    }

    dagger = ObjectGraph.create(MainSlavePipelineManagerModule.class);
    task = dagger.get(TaskWrapper.class);
    pipelineTask = (PipelineTask) ((TaskWrapper) task).getTask();
    pipelineName = pipelineTask.getName();
    pipelineManager = pipelineTask.getManager();
    runtimeInfo = dagger.get(RuntimeInfo.class);
    dagger.get(LogConfigurator.class).configure();
    LOG.info("-----------------------------------------------------------------");
    dagger.get(BuildInfo.class).log(LOG);
    LOG.info("-----------------------------------------------------------------");
    if (System.getSecurityManager() != null) {
      LOG.info("  Security Manager : ENABLED, policy file: {}", System.getProperty("java.security.policy"));
    } else {
      LOG.warn("  Security Manager : DISABLED");
    }
    LOG.info("-----------------------------------------------------------------");
    LOG.info("Starting ...");

    securityContext = new SecurityContext(dagger.get(RuntimeInfo.class), dagger.get(Configuration.class));
    securityContext.login();

    LOG.info("-----------------------------------------------------------------");
    LOG.info("  Kerberos enabled: {}", securityContext.getSecurityConfiguration().isKerberosEnabled());
    if (securityContext.getSecurityConfiguration().isKerberosEnabled()) {
      LOG.info("  Kerberos principal: {}", securityContext.getSecurityConfiguration().getKerberosPrincipal());
      LOG.info("  Kerberos keytab: {}", securityContext.getSecurityConfiguration().getKerberosKeytab());
    }
    LOG.info("-----------------------------------------------------------------");
    LOG.info("Starting ...");

    Subject.doAs(securityContext.getSubject(), new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        task.init();
        final Thread shutdownHookThread = new Thread("Main.shutdownHook") {
          @Override
          public void run() {
            LOG.debug("Stopping, reason: SIGTERM (kill)");
            task.stop();
          }
        };
        shutdownHookThread.setContextClassLoader(classLoader);
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        dagger.get(RuntimeInfo.class).setShutdownHandler(new ShutdownHandler(LOG, task, new ShutdownHandler.ShutdownStatus()));
        task.run();

        // this thread waits until the pipeline is shutdown
        waitingThread = new Thread() {
          @Override
          public void run() {
            try {
              task.waitWhileRunning();
              try {
                Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
              } catch (IllegalStateException ignored) {
                // thrown when we try and remove the shutdown
                // hook but it is already running
              }
              LOG.debug("Stopping, reason: programmatic stop()");
            } catch (Throwable throwable) {
              String msg = "Error running pipeline: " + throwable;
              LOG.error(msg, throwable);
            }
          }
        };
        waitingThread.setContextClassLoader(classLoader);
        waitingThread.setName("Pipeline-" + pipelineName);
        waitingThread.setDaemon(true);
        waitingThread.start();
        return null;
      }
    });
  }

  @Override
  public URI getServerURI() {
    URI serverURI;
    try {
      serverURI = pipelineTask.getWebServerTask().getServerURI();
    } catch (ServerNotYetRunningException ex) {
      throw new RuntimeException("Cannot retrieve URI of server" + ex.toString(), ex);
    }
    return serverURI;
  }

  @Override
  public void destroy() {
    task.stop();
  }

  public Pipeline getPipeline() {
    return ((PipelineInfo) runner).getPipeline();
  }

  @Override
  public List<URI> getWorkerList() throws URISyntaxException {
    List<URI> sdcURLList = new ArrayList<>();
    for (CallbackInfo callBackInfo : runner.getSlaveCallbackList()) {
      sdcURLList.add(new URI(callBackInfo.getSdcURL()));
    }
    return sdcURLList;
  }

  @Override
  public void startPipeline(String pipelineJson) throws Exception {
    throw new UnsupportedOperationException("This method is not supported. Use \"startPipeline()\" method");
  }

  @Override
  public String storeRules(String name, String tag, String ruleDefinitionsJsonString) throws Exception {
    throw new UnsupportedOperationException("This method is not supported.");
  }

}
