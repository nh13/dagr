/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package dagr.core.execsystem

import com.fulcrumgenomics.commons.util.{CaptureSystemStreams, LogLevel, Logger}
import dagr.core.UnitSpec
import dagr.core.execsystem.Terminal.Dimensions
import dagr.core.tasksystem.{NoOpInJvmTask, SimpleInJvmTask}
import org.scalatest.BeforeAndAfterAll

/**
  * Tests for TopLikeStatusReporter
  */
class TopLikeStatusReporterTest extends UnitSpec with CaptureSystemStreams with BeforeAndAfterAll {

  override def beforeAll(): Unit = Logger.level = LogLevel.Fatal
  override def afterAll(): Unit = Logger.level = LogLevel.Info

  private trait TestTerminal extends Terminal {
    override def dimensions: Dimensions = Dimensions(80, 60)
    override def supportsAnsi: Boolean =  true
  }

  private trait TwoLineTestTerminal extends Terminal {
    override def dimensions: Dimensions = Dimensions(80, 2)
    override def supportsAnsi: Boolean =  true
  }

  class WaitForIt extends SimpleInJvmTask {
    var completeTask = false
    override def run(): Unit = {
      while (!completeTask) {
        Thread.sleep(10)
      }
    }
  }

  private def getDefaultTaskManager(sleepMilliseconds: Int = 10): TaskManager = new TaskManager(
    taskManagerResources = SystemResources.infinite,
    scriptsDirectory = None,
    sleepMilliseconds = sleepMilliseconds
  )

  "Terminal" should "support ANSI codes" in {
    Terminal.supportsAnsi shouldBe true
  }

  "TopLikeStatusReporter" should "start and shutdown cleanly when no tasks are being managed" in {
    val taskManager = getDefaultTaskManager()
    val output = new StringBuilder()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = (str: String) => output.append(str)) with TestTerminal
    reporter.start()
    reporter.shutdown()
    output should not be 'empty
  }

  it should "start and shutdown cleanly when no tasks are being managed with a two line terminal" in {
    val taskManager = getDefaultTaskManager()
    val output = new StringBuilder()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = (str: String) => output.append(str)) with TwoLineTestTerminal
    reporter.start()
    reporter.shutdown()
    output should not be 'empty
    output.toString() should include("with 4 more lines not shown")
  }

  it should "start and shutdown cleanly when a single task is being managed" in {
    val taskManager = getDefaultTaskManager()
    val output = new StringBuilder()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = (str: String) => output.append(str)) with TestTerminal
    reporter.start()
    taskManager.addTask(new NoOpInJvmTask("Exit0Task"))
    taskManager.runToCompletion(failFast=true)
    reporter.shutdown()
    output should not be 'empty
    output.toString() should include("Exit0Task")
    output.toString() should include("1 Done")
  }

  it should "show the number of failed tasks" in {
    val taskOne = new SimpleInJvmTask {
      override def run(): Unit = throw new IllegalArgumentException("Failed")
    } withName "TaskOne"

    val output = new StringBuilder()
    val printMethod: String => Unit = (str: String) => output.append(str)
    val taskManager = getDefaultTaskManager()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = printMethod) with TestTerminal

    taskManager.addTask(taskOne)
    taskManager.runToCompletion(failFast=true)
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("0 Running")
    output.toString() should include("1 Failed")
    output.toString() should include("0 Done")
  }

  it should "show the number of running and completed tasks" in {
    val taskOne = new WaitForIt withName "TaskOne"

    val output = new StringBuilder()
    val printMethod: String => Unit = (str: String) => output.append(str)
    val taskManager = getDefaultTaskManager()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = printMethod) with TestTerminal

    taskManager.addTask(taskOne)
    taskManager.stepExecution()
    while (!taskManager.graphNodeStateFor(task=taskOne).contains(GraphNodeState.RUNNING)) {
      Thread.sleep(10)
      taskManager.stepExecution()
    }
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("1 Running")
    output.toString() should include("0 Done")
    taskOne.completeTask = true

    taskManager.runToCompletion(failFast=true)
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("0 Running")
    output.toString() should include("1 Done")
  }

  it should "show the number of eligible tasks" in {
    val taskOne = new WaitForIt withName "TaskOne"
    val taskTwo = new WaitForIt withName "TaskTwo"

    val output = new StringBuilder()
    val printMethod: String => Unit = (str: String) => output.append(str)
    val taskManager = new TaskManager(
      taskManagerResources = SystemResources(1.0, Long.MaxValue, Long.MaxValue), // one task at a time
      scriptsDirectory = None,
      sleepMilliseconds = 10
    )
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = printMethod) with TestTerminal

    // add the first task, which can be scheduled.
    taskManager.addTasks(taskOne, taskTwo)
    taskManager.stepExecution()
    while (!Seq(taskOne, taskTwo).exists(taskManager.graphNodeStateFor(_).contains(GraphNodeState.RUNNING))) {
      Thread.sleep(10)
      taskManager.stepExecution()
    }
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("TaskTwo")
    output.toString() should include("1 Running")
    output.toString() should include("0 Ineligible")
    output.toString() should include("1 Eligible")
    taskOne.completeTask = true
    taskTwo.completeTask = true

    taskManager.runToCompletion(failFast=true)
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("TaskTwo")
    output.toString() should include("0 Running")
    output.toString() should include("2 Done")
  }

  it should "show the number of ineligible tasks" in {
    val taskOne = new WaitForIt withName "TaskOne"
    val taskTwo = new WaitForIt withName "TaskTwo"
    taskOne ==> taskTwo

    val output = new StringBuilder()
    val printMethod: String => Unit = (str: String) => output.append(str)
    val taskManager = getDefaultTaskManager()
    val reporter = new TopLikeStatusReporter(taskManager = taskManager, print = printMethod) with TestTerminal

    // add the first task, which can be scheduled.
    taskManager.addTasks(taskOne, taskTwo)
    taskManager.stepExecution()
    while (!taskManager.graphNodeStateFor(task=taskOne).contains(GraphNodeState.RUNNING)) {
      Thread.sleep(10)
      taskManager.stepExecution()
    }
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should not include "TaskTwo"
    output.toString() should include("1 Running")
    output.toString() should include("1 Ineligible")
    output.toString() should include("0 Eligible")
    taskOne.completeTask = true
    taskTwo.completeTask = true

    taskManager.runToCompletion(failFast=true)
    reporter.refresh(print=printMethod)
    output should not be 'empty
    output.toString() should include("TaskOne")
    output.toString() should include("TaskTwo")
    output.toString() should include("0 Running")
    output.toString() should include("2 Done")
  }
}
