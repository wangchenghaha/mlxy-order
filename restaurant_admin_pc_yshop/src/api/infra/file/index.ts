export interface FilePresignedUrlRespVO {
  configId?: number
  uploadUrl: string
  url: string
}

export const getFilePresignedUrl = async (fileName: string): Promise<FilePresignedUrlRespVO> => ({
  uploadUrl: '',
  url: fileName
})

export const createFile = async (data: Recordable) => data

export const updateFile = async () => ({
  code: 1,
  message: '文件上传接口暂未接入'
})
