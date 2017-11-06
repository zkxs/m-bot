package net.michaelripley.emdashbot

import java.util.concurrent.ThreadLocalRandom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import net.dv8tion.jda.core.entities.ChannelType
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import net.dv8tion.jda.core.{AccountType, JDABuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.matching.Regex


object EmDashBot extends ListenerAdapter {

  val log: Logger = LoggerFactory.getLogger(getClass)
  val weightDelimiter = "\uD83D\uDC38" // U+1F438 Frog Face 🐸

  lazy val chooseRegex: Regex = """^!choose *(.*)$""".r
  lazy val splitRegex: String = """(?i)\s+or\s+|\s*\|\s*""" // intentionally left as a string for use with String.split()
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
    log.info("onReady()")
  }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = {

    val jdaAuthor = event.getAuthor
    val author = User.fromJDA(jdaAuthor)
    val message = event.getMessage
    val guild = event.getGuild
    val channel = event.getChannel
    val content = message.getContent
    val isBot = event.getAuthor.isBot
    val jda = event.getJDA

    val logString = s"onMessageReceived(): user=${author.name} message=$content"
    log.info(logString)


    if (!isBot) {
      if (event.isFromType(ChannelType.TEXT)) {
        content match {
          case "!shutdown" =>
            if (configuration.administrators.contains(author)) {
              jda.shutdown()
            } else {
              channel.sendMessage(s"${jdaAuthor.getAsMention}: :no_entry:").queue()
            }
          case _ if content.startsWith("""!echo""") => channel.sendMessage(content).queue()
          case "!whoami" => channel.sendMessage(s"${author.name} #${author.discriminator}").queue()
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
          case _ =>
        }
      }
    }
  }

  def stringToWeighted(string: String): Iterable[WeightedString] = {
    string.split(splitRegex).map{
      case weightRegex(s, null) => WeightedString(s)
      case weightRegex(s, w) => WeightedString(s, w.toFloat)
    }
  }

  /**
    * Randomly select a weighted choice
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
    * @param choices The choices
    * @param roll A roll between 0 and the sum of the choices' weights
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
