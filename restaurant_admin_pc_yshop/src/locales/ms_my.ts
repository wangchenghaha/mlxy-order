import base from './en'
import { restaurantMessages } from './restaurant'

export default {
  ...base,
  ...restaurantMessages.ms_my,
  common: {
    ...base.common,
    login: 'Log Masuk',
    required: 'Medan ini diperlukan',
    loginOut: 'Log keluar',
    reminder: 'Peringatan',
    loginOutMessage: 'Keluar daripada sistem?',
    ok: 'OK',
    save: 'Simpan',
    cancel: 'Batal',
    close: 'Tutup',
    success: 'Berjaya',
    query: 'Cari',
    reset: 'Tetapkan Semula',
    status: 'Status',
    createTime: 'Masa Cipta',
    updateTime: 'Masa Kemas Kini'
  },
  login: {
    ...base.login,
    welcome: restaurantMessages.ms_my.app.welcome,
    message: restaurantMessages.ms_my.app.welcome,
    username: 'Nama pengguna',
    password: 'Kata laluan',
    login: 'Log Masuk',
    relogin: 'Log masuk semula',
    remember: 'Ingat saya'
  },
  profile: {
    ...base.profile,
    info: {
      ...base.profile.info,
      resetPwd: 'Tukar kata laluan'
    },
    password: {
      oldPassword: 'Kata laluan lama',
      newPassword: 'Kata laluan baharu',
      confirmPassword: 'Sahkan kata laluan',
      oldPwdMsg: 'Sila masukkan kata laluan lama',
      newPwdMsg: 'Sila masukkan kata laluan baharu',
      cfPwdMsg: 'Sila sahkan kata laluan',
      pwdRules: 'Panjang 6 hingga 20 aksara',
      diffPwd: 'Kata laluan tidak sepadan'
    }
  },
  router: {
    ...base.router,
    login: 'Log Masuk',
    home: 'Laman Utama'
  },
  error: {
    noPermission: 'Maaf, anda tiada kebenaran untuk mengakses halaman ini.',
    pageError: 'Maaf, halaman yang anda lawati tidak wujud.',
    networkError: 'Maaf, pelayan melaporkan ralat.',
    returnToHome: 'Kembali ke laman utama'
  },
  lock: {
    ...base.lock,
    lockScreen: 'Kunci skrin'
  }
}
