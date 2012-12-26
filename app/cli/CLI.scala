package cli

import java.io._
import scala.sys.process.{ Process, ProcessIO, ProcessBuilder }
import play.api.libs.iteratee._

import concurrent.{ promise, Future, ExecutionContext }

/**
 * CLI defines helpers to deal with UNIX command with play iteratee.
 *
 * Depending on your needs, you can Enumerate / Pipe / Consume an UNIX command:
 *
 * `CLI.enumerate` is a way to create a stream from a command which generate output (it creates an [[play.api.libs.iteratee.Enumerator]])
 * `CLI.pipe` is a way to pipe a command which consume input and generate output (it creates an [[play.api.libs.iteratee.Enumeratee]])
 * `CLI.consume` creates a process which consume a stream - useful for side effect commands (it takes an [[play.api.libs.iteratee.Enumerator]])
 *
 **
 * Every process' stderr is logged in the console  with a "CLI" logger
 */
object CLI {


  /**
   * `CLI.enumerate` is a way to create a stream from a command which generate output - nothing is sent to the CLI input
   *
   * @param command the UNIX command
   * @return an [[play.api.libs.iteratee.Enumerator]] from the CLI output.
   *
   * @example {{{
   CLI.enumerate("find .")
   * }}}
   */
  def enumerate (command: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumerator[Array[Byte]] = new
  Enumerator[Array[Byte]] {
    def apply[A](i: Iteratee[Array[Byte], A]) = Enumerator.flatten[Array[Byte]] {
      val (process, stdin, stdout) = runProcess(command)
      stdout map { stdout =>
        Enumerator.fromStream(stdout, chunkSize).
        onDoneEnumerating { () =>
          val code = process.exitValue()
          logger.debug("exit("+code+") for command"+command)
          stdin map { _.close() }
          process.destroy()
        }
      }
    } apply i
  }

  /**
   * `CLI.pipe` is a way to pipe a command which consume input and generate output
   *
   * @param command the UNIX command
   * @return an [[play.api.libs.iteratee.Enumeratee]] from the CLI piping.
   *
   * @example {{{
     // Add an echo to an ogg audio stream.
     oggStream &> CLI.pipe("sox -t ogg - -t ogg - echo 0.5 0.7 60 1")
   * }}}
   */
  def pipe (command: ProcessBuilder, chunkSize: Int = 1024 * 8)(implicit ex: ExecutionContext): Enumeratee[Array[Byte], Array[Byte]] = { // FIXME this current version is not perfectly working yet
    new Enumeratee[Array[Byte], Array[Byte]] { 
      def applyOn[A](it: Iteratee[Array[Byte], A]): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = {
        val (process, stdin, stdout) = runProcess(command)

        Iteratee.flatten {
          (stdin zip stdout).map { case (cmdin, cmdout) =>
            import scala.concurrent.stm._

            val iteratee = Ref(it)

            logger.debug("writing")

            def result: Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = Cont(_ match {
              case Input.EOF => {
                logger.debug("done writing")
                cmdin.close()
                Done(iteratee.single.get, Input.EOF)
              }
              case Input.Empty => result
              case Input.El(e) => {
                cmdin.write(e)
                result
              }
            })

            concurrent.future {
              logger.debug("reading")
              var end = false
              while (!end) {
                val buffer = new Array[Byte](chunkSize)
                val chunk = cmdout.read(buffer) match {
                  case -1 => None
                  case read =>
                    val input = new Array[Byte](read)
                    System.arraycopy(buffer, 0, input, 0, read)
                    Some(input)
                }
                chunk map { bytes =>
                  val it = iteratee.single.get.feed(Input.El(bytes))
                  iteratee.single.swap(Iteratee.flatten(it))
                }
                end = chunk.isDefined
              }
              logger.debug("done reading")
              cmdout.close()
            }
            result mapDone { _ =>
              cmdin.close()
              val code = process.exitValue()
              logger.debug("exit("+code+") for command"+command)
              process.destroy()
            }
            result
          }
        }
      }
    }
  }

  /**
   * Create an Enumeratee where:
   * - all input sent to this Enumeratee are plugged to the cmdin (cmdin: Iteratee)
   * - all input coming from cmdout (cmdout: Enumerator) are streamed to the output of this Enumeratee
   */
  def enumerateePipe (
    cmdin: Iteratee[Array[Byte], Unit], 
    cmdout: Enumerator[Array[Byte]]
  )(implicit ex: ExecutionContext) : Enumeratee[Array[Byte], Array[Byte]] = {

    // enumerateePipe: FIXME Not Implemented Yet!

    Enumerator() |>>> cmdin // FIXME (temporary cmdin consuming)
    
    new Enumeratee[Array[Byte], Array[Byte]] {
      def applyOn[A] (it: Iteratee[Array[Byte], A]): Iteratee[Array[Byte], Iteratee[Array[Byte], A]] = {
        Enumeratee.passAlong[Array[Byte]] &> Iteratee.flatten(cmdout(it))
      }
    }
  }


  /**
   * `CLI.consume` creates a process which consume a stream - useful for side effect commands 
   *
   * the CLI stdout is logged in the console ("CLI" logger)
   *
   * @param command the UNIX command
   * @param enumerator the enumerator producing data consumed by the CLI
   *
   * @example {{{
     CLI.consume("aSideEffectCommand")(anEnumerator)
   * }}}
   */
  def consume (command: ProcessBuilder)(enumerator: Enumerator[Array[Byte]])(implicit ex: ExecutionContext) = {
    enumerator |>>> Iteratee.flatten[Array[Byte], Unit] {
      val (process, stdin, stdout) = runProcess(command)

      stdout map { out => logStd(out)(logger.info) }

      stdin map { cmdin =>
        Iteratee.foreach[Array[Byte]] { bytes =>
          cmdin.write(bytes)
          } mapDone { _ =>
            cmdin.close()
            val code = process.exitValue()
            logger.debug("exit("+code+") for command"+command)
            stdout map { _.close() }
            process.destroy()
          }
      }
    }
  }


  private val logger = play.api.Logger("CLI")

  private def logStd (stream: InputStream)(loggerF: (=>String)=>Unit ) {
    val br = new java.io.BufferedReader(new InputStreamReader(stream))
    var read = br.readLine()
    while(read != null) {
      loggerF(read)
      read = br.readLine()
    }
    stream.close()
  }

  /**
   * Run a process from a command 
   * @return a (process, future of stdin, future of stdout)
   */
  private def runProcess (command: ProcessBuilder)(implicit ex: ExecutionContext): (Process, Future[OutputStream], Future[InputStream]) = {
    val promiseStdin = promise[OutputStream]()
    val promiseStdout = promise[InputStream]()

    val process = command run new ProcessIO(
      (stdin: OutputStream) => promiseStdin.success(stdin),
      (stdout: InputStream) => promiseStdout.success(stdout),
      logStd(_)(logger.warn)
    )
    (process, promiseStdin.future, promiseStdout.future)
  }
}
