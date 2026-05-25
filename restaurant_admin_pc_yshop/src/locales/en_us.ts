import base from './en'
import { restaurantMessages } from './restaurant'

export default {
  ...base,
  ...restaurantMessages.en_us,
  login: {
    ...base.login,
    welcome: restaurantMessages.en_us.app.welcome,
    message: restaurantMessages.en_us.app.welcome
  }
}
