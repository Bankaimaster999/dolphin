// Copyright 2016 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include <algorithm>

#include "Common/Assert.h"
#include "VideoBackends/Vulkan/CommandBufferManager.h"
#include "VideoBackends/Vulkan/Texture2D.h"
#include "VideoBackends/Vulkan/VulkanContext.h"

namespace Vulkan
{
Texture2D::Texture2D(u32 width, u32 height, u32 levels, u32 layers, VkFormat format,
                     VkSampleCountFlagBits samples, VkImageViewType view_type, VkImage image,
                     VkDeviceMemory device_memory, VkDeviceSize memory_offset, VkImageView view)
    : m_width(width), m_height(height), m_levels(levels), m_layers(layers), m_format(format),
      m_samples(samples), m_view_type(view_type), m_image(image), m_device_memory(device_memory),
      m_memory_offset(memory_offset), m_view(view)
{
}

Texture2D::~Texture2D()
{
  g_command_buffer_mgr->DeferImageViewDestruction(m_view);

  // If we don't have device memory allocated, the image is not owned by us (e.g. swapchain)
  if (m_device_memory != VK_NULL_HANDLE)
  {
    g_command_buffer_mgr->DeferImageDestruction(m_image);
    //g_command_buffer_mgr->DeferDeviceMemoryDestruction(m_device_memory);
    g_command_buffer_mgr->Free(m_device_memory, m_memory_offset);
    m_device_memory = VK_NULL_HANDLE;
  }
}

std::unique_ptr<Texture2D> Texture2D::Create(u32 width, u32 height, u32 levels, u32 layers,
                                             VkFormat format, VkSampleCountFlagBits samples,
                                             VkImageViewType view_type, VkImageTiling tiling,
                                             VkImageUsageFlags usage)
{
  VkImageCreateInfo image_info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                                  nullptr,
                                  0,
                                  VK_IMAGE_TYPE_2D,
                                  format,
                                  {width, height, 1},
                                  levels,
                                  layers,
                                  samples,
                                  tiling,
                                  usage,
                                  VK_SHARING_MODE_EXCLUSIVE,
                                  0,
                                  nullptr,
                                  VK_IMAGE_LAYOUT_UNDEFINED};

  VkImage image = VK_NULL_HANDLE;
  VkResult res = vkCreateImage(g_vulkan_context->GetDevice(), &image_info, nullptr, &image);
  if (res != VK_SUCCESS)
  {
    LOG_VULKAN_ERROR(res, "vkCreateImage failed: ");
    return nullptr;
  }

  // Allocate memory to back this texture, we want device local memory in this case
  VkMemoryRequirements memory_requirements;
  vkGetImageMemoryRequirements(g_vulkan_context->GetDevice(), image, &memory_requirements);

  VkDeviceMemory device_memory;
  size_t offset = g_command_buffer_mgr->Allocate(memory_requirements, &device_memory);
  if(offset == VulkanDeviceAllocator::ALLOCATE_FAILED)
  {
    LOG_VULKAN_ERROR(res, "vkAllocateMemory failed: ");
    vkDestroyImage(g_vulkan_context->GetDevice(), image, nullptr);
    return nullptr;
  }

  res = vkBindImageMemory(g_vulkan_context->GetDevice(), image, device_memory, offset);
  if (res != VK_SUCCESS)
  {
    LOG_VULKAN_ERROR(res, "vkBindImageMemory failed: ");
    vkDestroyImage(g_vulkan_context->GetDevice(), image, nullptr);
    vkFreeMemory(g_vulkan_context->GetDevice(), device_memory, nullptr);
    return nullptr;
  }

  VkImageViewCreateInfo view_info = {
      VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      nullptr,
      0,
      image,
      view_type,
      format,
      {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
       VK_COMPONENT_SWIZZLE_IDENTITY},
      {Util::IsDepthFormat(format) ? static_cast<VkImageAspectFlags>(VK_IMAGE_ASPECT_DEPTH_BIT) :
                                     static_cast<VkImageAspectFlags>(VK_IMAGE_ASPECT_COLOR_BIT),
       0, levels, 0, layers}};

  VkImageView view = VK_NULL_HANDLE;
  res = vkCreateImageView(g_vulkan_context->GetDevice(), &view_info, nullptr, &view);
  if (res != VK_SUCCESS)
  {
    LOG_VULKAN_ERROR(res, "vkCreateImageView failed: ");
    vkDestroyImage(g_vulkan_context->GetDevice(), image, nullptr);
    vkFreeMemory(g_vulkan_context->GetDevice(), device_memory, nullptr);
    return nullptr;
  }

  return std::make_unique<Texture2D>(width, height, levels, layers, format, samples, view_type,
                                     image, device_memory, offset, view);
}

std::unique_ptr<Texture2D> Texture2D::CreateFromExistingImage(u32 width, u32 height, u32 levels,
                                                              u32 layers, VkFormat format,
                                                              VkSampleCountFlagBits samples,
                                                              VkImageViewType view_type,
                                                              VkImage existing_image)
{
  // Only need to create the image view, this is mainly for swap chains.
  VkImageViewCreateInfo view_info = {
      VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      nullptr,
      0,
      existing_image,
      view_type,
      format,
      {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
       VK_COMPONENT_SWIZZLE_IDENTITY},
      {Util::IsDepthFormat(format) ? static_cast<VkImageAspectFlags>(VK_IMAGE_ASPECT_DEPTH_BIT) :
                                     static_cast<VkImageAspectFlags>(VK_IMAGE_ASPECT_COLOR_BIT),
       0, levels, 0, layers}};

  // Memory is managed by the owner of the image.
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  VkResult res = vkCreateImageView(g_vulkan_context->GetDevice(), &view_info, nullptr, &view);
  if (res != VK_SUCCESS)
  {
    LOG_VULKAN_ERROR(res, "vkCreateImageView failed: ");
    return nullptr;
  }

  return std::make_unique<Texture2D>(width, height, levels, layers, format, samples, view_type,
                                     existing_image, memory, 0, view);
}

void Texture2D::OverrideImageLayout(VkImageLayout new_layout)
{
  m_layout = new_layout;
}

void Texture2D::TransitionToLayout(VkCommandBuffer command_buffer, VkImageLayout new_layout)
{
  if (m_layout == new_layout)
    return;

  VkImageMemoryBarrier barrier = {
      VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,  // VkStructureType            sType
      nullptr,                                 // const void*                pNext
      0,                                       // VkAccessFlags              srcAccessMask
      0,                                       // VkAccessFlags              dstAccessMask
      m_layout,                                // VkImageLayout              oldLayout
      new_layout,                              // VkImageLayout              newLayout
      VK_QUEUE_FAMILY_IGNORED,                 // uint32_t                   srcQueueFamilyIndex
      VK_QUEUE_FAMILY_IGNORED,                 // uint32_t                   dstQueueFamilyIndex
      m_image,                                 // VkImage                    image
      {Util::GetImageAspectForFormat(m_format), 0, m_levels, 0,
       m_layers}  // VkImageSubresourceRange    subresourceRange
  };

  // srcStageMask -> Stages that must complete before the barrier
  // dstStageMask -> Stages that must wait for after the barrier before beginning
  VkPipelineStageFlags srcStageMask, dstStageMask;
  switch (m_layout)
  {
  case VK_IMAGE_LAYOUT_UNDEFINED:
    // Layout undefined therefore contents undefined, and we don't care what happens to it.
    barrier.srcAccessMask = 0;
    srcStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;

  case VK_IMAGE_LAYOUT_PREINITIALIZED:
    // Image has been pre-initialized by the host, so ensure all writes have completed.
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_HOST_BIT;
    break;

  case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
    // Image was being used as a color attachment, so ensure all writes have completed.
    barrier.srcAccessMask =
        VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    break;

  case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
    // Image was being used as a depthstencil attachment, so ensure all writes have completed.
    barrier.srcAccessMask =
        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    srcStageMask =
        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    break;

  case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:
    // Image was being used as a shader resource, make sure all reads have finished.
    barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:
    // Image was being used as a copy source, ensure all reads have finished.
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
    // Image was being used as a copy destination, ensure all writes have finished.
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  default:
    srcStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;
  }

  switch (new_layout)
  {
  case VK_IMAGE_LAYOUT_UNDEFINED:
    barrier.dstAccessMask = 0;
    dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;

  case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
    barrier.dstAccessMask =
        VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    break;

  case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
    barrier.dstAccessMask =
        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dstStageMask =
        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    break;

  case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR:
    srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;

  default:
    dstStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    break;
  }

  // If we were using a compute layout, the stages need to reflect that
  switch (m_compute_layout)
  {
  case ComputeImageLayout::Undefined:
    break;
  case ComputeImageLayout::ReadOnly:
    barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    srcStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  case ComputeImageLayout::WriteOnly:
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  case ComputeImageLayout::ReadWrite:
    barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  }
  m_compute_layout = ComputeImageLayout::Undefined;

  vkCmdPipelineBarrier(command_buffer, srcStageMask, dstStageMask, 0, 0, nullptr, 0, nullptr, 1,
                       &barrier);

  m_layout = new_layout;
}

void Texture2D::TransitionToLayout(VkCommandBuffer command_buffer, ComputeImageLayout new_layout)
{
  ASSERT(new_layout != ComputeImageLayout::Undefined);
  if (m_compute_layout == new_layout)
    return;

  VkImageMemoryBarrier barrier = {
      VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,  // VkStructureType            sType
      nullptr,                                 // const void*                pNext
      0,                                       // VkAccessFlags              srcAccessMask
      0,                                       // VkAccessFlags              dstAccessMask
      m_layout,                                // VkImageLayout              oldLayout
      VK_IMAGE_LAYOUT_GENERAL,                 // VkImageLayout              newLayout
      VK_QUEUE_FAMILY_IGNORED,                 // uint32_t                   srcQueueFamilyIndex
      VK_QUEUE_FAMILY_IGNORED,                 // uint32_t                   dstQueueFamilyIndex
      m_image,                                 // VkImage                    image
      {Util::GetImageAspectForFormat(m_format), 0, m_levels, 0,
       m_layers}  // VkImageSubresourceRange    subresourceRange
  };

  VkPipelineStageFlags srcStageMask, dstStageMask;
  switch (m_layout)
  {
  case VK_IMAGE_LAYOUT_UNDEFINED:
    // Layout undefined therefore contents undefined, and we don't care what happens to it.
    barrier.srcAccessMask = 0;
    srcStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;

  case VK_IMAGE_LAYOUT_PREINITIALIZED:
    // Image has been pre-initialized by the host, so ensure all writes have completed.
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_HOST_BIT;
    break;

  case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
    // Image was being used as a color attachment, so ensure all writes have completed.
    barrier.srcAccessMask =
        VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    break;

  case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
    // Image was being used as a depthstencil attachment, so ensure all writes have completed.
    barrier.srcAccessMask =
        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    srcStageMask =
        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    break;

  case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:
    // Image was being used as a shader resource, make sure all reads have finished.
    barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:
    // Image was being used as a copy source, ensure all reads have finished.
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
    // Image was being used as a copy destination, ensure all writes have finished.
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    break;

  default:
    srcStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    break;
  }

  switch (new_layout)
  {
  case ComputeImageLayout::ReadOnly:
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    dstStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  case ComputeImageLayout::WriteOnly:
    barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    dstStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  case ComputeImageLayout::ReadWrite:
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    dstStageMask = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    break;
  default:
    dstStageMask = 0;
    break;
  }

  m_layout = barrier.newLayout;
  m_compute_layout = new_layout;

  vkCmdPipelineBarrier(command_buffer, srcStageMask, dstStageMask, 0, 0, nullptr, 0, nullptr, 1,
                       &barrier);
}

}  // namespace Vulkan
