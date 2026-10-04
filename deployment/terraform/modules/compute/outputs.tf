output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "alb_listener_arn" {
  value = aws_lb_listener.http.arn
}

output "alb_arn_suffix" {
  value = aws_lb.this.arn_suffix
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_name" {
  value = aws_ecs_service.this.name
}
