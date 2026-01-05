import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '/dlq',
    pathMatch: 'full'
  },
  {
    path: 'dlq',
    loadComponent: () => import('./components/dlq/dlq.component').then(m => m.DlqComponent)
  },
  {
    path: 'pubsub',
    loadComponent: () => import('./components/pubsub/pubsub.component').then(m => m.PubsubComponent)
  },
  {
    path: 'request-reply',
    loadComponent: () => import('./components/request-reply/request-reply.component').then(m => m.RequestReplyComponent)
  },
  {
    path: 'work-queue',
    loadComponent: () => import('./components/work-queue/work-queue.component').then(m => m.WorkQueueComponent)
  },
  {
    path: 'fan-out',
    loadComponent: () => import('./components/fan-out/fan-out.component').then(m => m.FanOutComponent)
  },
  {
    path: 'topic-routing',
    loadComponent: () => import('./components/topic-routing/topic-routing.component').then(m => m.TopicRoutingComponent)
  },
  {
    path: 'pubsub-topic-routing',
    loadComponent: () => import('./components/pubsub-topic-routing/pubsub-topic-routing.component').then(m => m.PubsubTopicRoutingComponent)
  },
  {
    path: 'content-routing',
    loadComponent: () => import('./components/content-based-routing/content-based-routing.component').then(m => m.ContentBasedRoutingComponent)
  },
  {
    path: 'scheduled-messages',
    loadComponent: () => import('./components/scheduled-messages/scheduled-messages.component').then(m => m.ScheduledMessagesComponent)
  },
  {
    path: 'per-key-serialized',
    loadComponent: () => import('./components/per-key-serialized/per-key-serialized.component').then(m => m.PerKeySerializedComponent)
  },
  {
    path: 'token-bucket',
    loadComponent: () => import('./components/token-bucket/token-bucket.component').then(m => m.TokenBucketComponent)
  },
  {
    path: '**',
    redirectTo: '/dlq'
  }
];
