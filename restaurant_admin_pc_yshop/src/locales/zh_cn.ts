import base from './zh-CN'
import { restaurantMessages } from './restaurant'

export default {
  ...base,
  ...restaurantMessages.zh_cn,
  login: {
    ...base.login,
    welcome: restaurantMessages.zh_cn.app.welcome,
    message: restaurantMessages.zh_cn.app.welcome
  }
}
