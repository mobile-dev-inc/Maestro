package maestro.cli.model

import maestro.cli.api.UploadStatus

enum class FlowStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR,
    CANCELED,
    WARNING;

    companion object {

        fun from(status: UploadStatus.Status) = when (status) {
            UploadStatus.Status.PENDING -> PENDING
            UploadStatus.Status.RUNNING -> RUNNING
            UploadStatus.Status.SUCCESS -> SUCCESS
            UploadStatus.Status.ERROR -> ERROR
            UploadStatus.Status.CANCELED -> CANCELED
            UploadStatus.Status.WARNING -> WARNING
        }

    }
}