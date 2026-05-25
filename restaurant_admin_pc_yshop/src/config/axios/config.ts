const config: {
  base_url: string
  result_code: number | string
  default_headers: AxiosHeaders
  request_timeout: number
} = {
  /**
   * api请求基础路径
   */
  base_url: `${
    import.meta.env.VITE_BASE_URL ||
    (import.meta.env.DEV ? 'http://127.0.0.1:8080' : window.location.origin)
  }${import.meta.env.VITE_API_URL || '/api'}`,
  /**
   * 接口成功返回状态码
   */
  result_code: 0,

  /**
   * 接口请求超时时间
   */
  request_timeout: 30000,

  /**
   * 默认接口请求类型
   * 可选值：application/x-www-form-urlencoded multipart/form-data
   */
  default_headers: 'application/json'
}

export { config }
