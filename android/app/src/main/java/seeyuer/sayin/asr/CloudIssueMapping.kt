package seeyuer.sayin.asr

internal fun CloudIssue.recognitionIssue(): RecognitionIssue = when (this) {
    CloudIssue.Configuration, CloudIssue.Storage -> RecognitionIssue.CloudConfiguration
    CloudIssue.Network -> RecognitionIssue.CloudNetwork
    CloudIssue.Authentication -> RecognitionIssue.CloudAuthentication
    CloudIssue.AccessDenied -> RecognitionIssue.CloudAccessDenied
    CloudIssue.Quota -> RecognitionIssue.CloudQuota
    CloudIssue.Timeout -> RecognitionIssue.CloudTimeout
    CloudIssue.Protocol -> RecognitionIssue.CloudProtocol
    CloudIssue.Service -> RecognitionIssue.CloudService
}
