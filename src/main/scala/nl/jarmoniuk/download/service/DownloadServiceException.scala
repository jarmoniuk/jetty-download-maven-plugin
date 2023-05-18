package nl.jarmoniuk.download.service

case class DownloadServiceException(httpStatus: Int, message: String) extends Exception(message) {
}
