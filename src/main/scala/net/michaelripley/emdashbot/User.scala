package net.michaelripley.emdashbot

case class User(
  name: String,
  discriminator: Int
)

object User {
  def fromJDA(user: net.dv8tion.jda.core.entities.User): User = {
    User(user.getName, user.getDiscriminator.toInt)
  }
}
