import SparkMD5 from 'spark-md5'

/**
 * 文件哈希工具。
 *
 * 秒传与断点续传都依赖「上传前先算出整文件 MD5」：服务端凭这个 MD5 判断能否跳过上传，
 * 合并分片后也会重算一遍与它核对。浏览器原生的 SubtleCrypto 只支持 SHA 系列、不支持 MD5，
 * 而服务端历史数据与 im_file.md5 列用的都是 MD5，因此这里引入 spark-md5 做增量计算。
 *
 * 增量（分片喂给 spark）而不是一次性 readAsArrayBuffer：大视频动辄上 GB，
 * 一次性读进内存会直接把标签页撑爆，分片读取内存占用恒定。
 */

/** 计算哈希时的读取分片大小，2MB 在进度粒度与内存占用之间取平衡 */
const HASH_CHUNK_SIZE = 2 * 1024 * 1024

/**
 * 增量计算整个文件的 MD5。
 *
 * @param {File|Blob} file       待计算的文件
 * @param {(percent:number)=>void} [onProgress] 进度回调，0-100 的整数
 * @returns {Promise<string>}    32 位小写十六进制 MD5
 */
export async function computeFileMd5(file, onProgress) {
  const spark = new SparkMD5.ArrayBuffer()
  let offset = 0
  try {
    while (offset < file.size) {
      const slice = file.slice(offset, offset + HASH_CHUNK_SIZE)
      // Blob.arrayBuffer() 现代浏览器均支持，比 FileReader 回调嵌套清爽
      spark.append(await slice.arrayBuffer())
      offset += HASH_CHUNK_SIZE
      if (onProgress) {
        onProgress(Math.min(100, Math.round((Math.min(offset, file.size) / file.size) * 100)))
      }
    }
    return spark.end()
  } finally {
    // 释放 spark 内部缓冲，避免大文件计算后内存迟迟不回收
    spark.destroy()
  }
}
