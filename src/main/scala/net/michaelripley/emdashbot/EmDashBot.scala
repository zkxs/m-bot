package net.michaelripley.emdashbot

import java.util.concurrent.ThreadLocalRandom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import net.dv8tion.jda.core.entities.ChannelType
import net.dv8tion.jda.core.events.{ReadyEvent, ShutdownEvent}
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.exceptions.InsufficientPermissionException
import net.dv8tion.jda.core.hooks.ListenerAdapter
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.utils.PermissionUtil
import net.dv8tion.jda.core.{AccountType, JDABuilder, Permission}
import org.slf4j.{Logger, LoggerFactory}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.matching.Regex


object EmDashBot extends ListenerAdapter {

  val log: Logger = LoggerFactory.getLogger(getClass)
  val weightDelimiter = "\uD83D\uDC38" // U+1F438 Frog Face 🐸

  lazy val chooseRegex: Regex = """^!choose *(.*)$""".r
  lazy val splitRegex: Regex = """(?i)\s+or\s+|\s*\|\s*""".r
  lazy val weightRegex: Regex = s"""^(.*?)(?:$weightDelimiter(.*))?$$""".r

  // YA
  lazy val mapper: ObjectMapper = new ObjectMapper(new YAMLFactory)
    .registerModule(DefaultScalaModule)

  var configuration: Configuration = _

  def main(args: Array[String]): Unit = {

    val environment = if (args.contains("test")) {
      "test"
    } else {
      "prod"
    }

    // load environment configs
    configuration = mapper.readValue(getClass.getResourceAsStream(s"/secret-shit/secret-shit-$environment.yaml"), classOf[Configuration])

    // start bot
    new JDABuilder(AccountType.BOT)
      .setToken(configuration.token)
      .addEventListener(EmDashBot)
      .buildAsync()
  }

  override def onReady(event: ReadyEvent): Unit = {
    val shutdown: Runnable = () => {
      event.getJDA.shutdown()
      log.info("JDA shutdown hook invoked")
    }
    Runtime.getRuntime.addShutdownHook(new Thread(shutdown, "JDA shutdown hook"))
    log.info("onReady()")
  }


  override def onShutdown(event: ShutdownEvent): Unit = {
    log.info("onShutdown()")
  }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = {

    val jdaAuthor = event.getAuthor
    val author = User.fromJDA(jdaAuthor)
    val message = event.getMessage
    val guild = event.getGuild
    val content = message.getContentRaw
    val isBot = event.getAuthor.isBot
    val jda = event.getJDA

    val logString = s"onMessageReceived(): user=${author.name} message=$content"
    log.info(logString)


    if (!isBot) {
      if (event.isFromType(ChannelType.TEXT)) {
        val channel = event.getTextChannel

        content match {
          case "!shutdown" =>
            if (configuration.administrators.contains(author)) {
              jda.shutdown()
            } else {
              channel.sendMessage(s"${jdaAuthor.getAsMention}: :no_entry:").queue()
            }
          case "!whoami" => channel.sendMessage(s"${author.name}#${author.discriminator}").queue()
          case "!moe" =>
            if (configuration.dinguses.contains(author)) {
              channel.sendMessage("http://1.bp.blogspot.com/-i2AJd-eAdjY/TjLS65zRb-I/AAAAAAAAB9g/DSNg3RoNzoo/s1600/moe-howard-7.jpg").queue()
            } else {
              channel.sendMessage("https://cdn.discordapp.com/attachments/85176687381196800/179404570143883265/6-4aQ-Qp_400x400.png").queue()
            }
          case "!foo" => channel.sendMessage("bar").queue()
          case chooseRegex(arg) =>
            val chooseMessage = try {
              Some(chooseString(stringToWeighted(arg)))
            } catch {
              case e: NumberFormatException => Some(s"Error parsing number ${e.getMessage.toLowerCase}")
              case e: IllegalArgumentException => Some(s"Error: ${e.getMessage}")
              case _: NoSuchElementException => Some("You're doing it wrong.")
            }
            chooseMessage.foreach(chooseMessage => { // if Some(message)

              val emotes = message.getEmotes.asScala.toSet.filter(_.getGuild == guild) // guild is null for fake emotes

              val result = if (emotes.isEmpty) {
                chooseMessage
              } else {
                var result = chooseMessage
                emotes.foreach(emote => {
                  result = result.replaceAllLiterally(s":${emote.getName}:", emote.getAsMention)
                })
                result
              }

              log.info(s"sending: $result")

              channel.sendMessage(result).queue()
            })
          case _ if content.startsWith("!echo") => channel.sendMessage(content).queue()
          case _ if content.startsWith("!invite") =>
            log.info("got !invite")

            val everyone = guild.getPublicRole
            if (everyone.hasPermission(channel, Permission.MESSAGE_READ)) {
              // we don't have to do anything
              channel.sendMessage(s"${channel.getAsMention} is publicly readable").queue()
            } else {
              // all secret other channels on the server
              val channels = guild.getTextChannels.asScala
                .filter(!everyone.hasPermission(_, Permission.MESSAGE_READ)) // private
                .filter(_.getId != channel.getId) // not this channel

              // all roles that can read this channel
              val roles = guild.getRoles.asScala
                .filter(_.getPermissions.isEmpty) // no server-wide permissions
                .filter(_.hasPermission(channel, Permission.MESSAGE_READ)) // can read this channel
                //                .filter(!_.isHoisted) // is not distinguished
                .filter(role => !channels.exists(channel => role.hasPermission(channel, Permission.MESSAGE_READ))) // role does not grant read in any other channels

              log.info(s"other secret channels: ${channels.map(_.getName).mkString(", ")}")
              log.info(s"matching roles: ${roles.map(_.getName).mkString(", ")}")

              if (roles.isEmpty) {
                channel.sendMessage("No appropriate roles found.").queue()
              } else {
                if (roles.length > 1) {
                  channel.sendMessage(s"ambiguous roles: ${roles.map('`' + _.getName + '`').mkString(", ")}").queue()
                } else {
                  val role = roles.head
                  // users specified in command
                  val users = message.getMentionedMembers(guild).asScala
                  if (users.isEmpty) {
                    channel.sendMessage(s"You didn't mention any users…").queue()
                  } else {
                    if (!guild.getSelfMember.canInteract(role)) {
                      channel.sendMessage(s"I do not have access to give the `${role.getName}` role.").queue()
                    } else {
                      // users to give this role to
                      val usersWhoNeedRole = users
                        .filter(!_.getRoles.contains(role))

                      log.info(s"users who need ${role.getName} role: ${usersWhoNeedRole.map(_.getUser).map(u => s"${u.getName}#${u.getDiscriminator}").mkString(", ")}")

                      if (usersWhoNeedRole.isEmpty) {
                        channel.sendMessage(s"All of those users already have the `${role.getName}` role.").queue()
                      } else {
                        val controller = guild.getController

                        // do role updates
                        try {
                          val futures = usersWhoNeedRole.map(user => {
                            val restAction = controller.addSingleRoleToMember(user, role)
                            restAction.reason(s"${jdaAuthor.getName}#${jdaAuthor.getDiscriminator} used !invite in #${channel.getName}")
                            restAction.submit()
                          })

                          // notify that we are done
                          RequestFuture.allOf(futures.asJavaCollection)
                            .thenRun(() => channel.sendMessage(s"gave `${role.getName}` role to ${usersWhoNeedRole.map(_.getAsMention).mkString(", ")}").queue())

                        } catch {
                          case e: InsufficientPermissionException => channel.sendMessage(s"I do not have access to give the `${role.getName}` role, as I am missing the `${e.getPermission.getName}` permission.").queue()
                        }
                      }
                    }
                  }
                }
              }
            }
          case _ =>
        }
      }
    }
  }

  def stringToWeighted(string: String): Iterable[WeightedString] = {
    splitRegex.split(string).map {
      case weightRegex(s, null) => WeightedString(s)
      case weightRegex(s, w) => WeightedString(s, w.toFloat)
    }
  }

  /**
    * Randomly select a weighted choice
    *
    * @param choices The choices
    * @return The value of a single choice
    */
  def chooseString(choices: Iterable[WeightedString]): String = {

    // streams are lazy and cached
    val weights = choices.toStream.map(_.weight)

    if (weights.exists(_ < 0)) {
      throw new IllegalArgumentException("negative weights are not allowed")
    }

    val totalWeight = weights.sum
    val roll = ThreadLocalRandom.current().nextFloat() * totalWeight

    chooseString(choices, roll)
  }

  /**
    * Given a random roll, select a choice
    *
    * @param choices The choices
    * @param roll    A roll between 0 and the sum of the choices' weights
    * @return The value of a single choice
    * @throws NoSuchElementException for empty input
    */
  @tailrec
  def chooseString(choices: Iterable[WeightedString], roll: Float): String = {
    val choice = choices.head
    val weight = choice.weight
    val tail = choices.tail

    if (roll < weight || tail.isEmpty) {
      // if it is this roll
      // OR if the roll is higher than the max and this is the last choice
      choice.string
    } else {
      chooseString(tail, roll - weight)
    }
  }
}

sealed case class WeightedString(string: String, weight: Float = 1.0f)
