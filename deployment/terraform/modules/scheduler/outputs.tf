output "schedule_arn" {
  value = aws_scheduler_schedule.release_expired_reservations.arn
}
