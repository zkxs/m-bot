package net.michaelripley.emdashbot

case class Configuration(
  token: String,
  username: String,
  administrators: Set[User],
  dinguses: Set[User]
)
