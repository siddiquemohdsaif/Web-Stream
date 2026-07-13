#include "native-vulkan-renderer.h"
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/asset_manager.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>
#include <sstream>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CameraVulkan", __VA_ARGS__)
#define RLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "RenderJPEG_X", __VA_ARGS__)
#define RLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RenderJPEG_X", __VA_ARGS__)

namespace {
struct Texture { VkImage image{}; VkDeviceMemory memory{}; VkImageView view{}; int w{}, h{}; };
struct Renderer {
    VkInstance instance{}; VkSurfaceKHR surface{}; VkPhysicalDevice physical{};
    VkDevice device{}; uint32_t queueFamily{}; VkQueue queue{};
    VkSwapchainKHR swapchain{}; VkFormat swapFormat{}; VkExtent2D extent{};
    VkSurfaceTransformFlagBitsKHR surfaceTransform{VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR};
    std::vector<VkImage> swapImages; std::vector<VkImageView> swapViews;
    VkRenderPass renderPass{}; VkDescriptorSetLayout setLayout{}; VkPipelineLayout pipelineLayout{};
    VkPipeline pipeline{}; std::vector<VkFramebuffer> framebuffers;
    VkCommandPool commandPool{}; VkCommandBuffer command{};
    VkSemaphore acquired{}, finished{}; VkFence fence{};
    VkDescriptorPool descriptorPool{}; VkDescriptorSet descriptor{}; VkSampler sampler{};
    std::array<Texture,3> textures{}; VkBuffer staging{}; VkDeviceMemory stagingMemory{}; size_t stagingSize{};
    AAssetManager* assets{}; bool ready{};
} r;
std::mutex mutex;

bool ok(VkResult value, const char* what) {
    if (value == VK_SUCCESS) return true;
    LOGE("%s failed: %d", what, value); return false;
}
uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties p{}; vkGetPhysicalDeviceMemoryProperties(r.physical, &p);
    for (uint32_t i=0;i<p.memoryTypeCount;i++) if ((bits&(1u<<i)) && (p.memoryTypes[i].propertyFlags&flags)==flags) return i;
    return UINT32_MAX;
}
std::vector<uint8_t> asset(const char* name) {
    AAsset* a=AAssetManager_open(r.assets,name,AASSET_MODE_BUFFER); if(!a) return {};
    size_t n=AAsset_getLength(a); std::vector<uint8_t> out(n); AAsset_read(a,out.data(),n); AAsset_close(a); return out;
}
VkShaderModule shader(const char* name) {
    auto bytes=asset(name); if(bytes.empty()) return VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; ci.codeSize=bytes.size(); ci.pCode=reinterpret_cast<const uint32_t*>(bytes.data());
    VkShaderModule module{}; return ok(vkCreateShaderModule(r.device,&ci,nullptr,&module),name)?module:VK_NULL_HANDLE;
}
void destroyTextures() {
    if(!r.device)return; vkDeviceWaitIdle(r.device);
    if(r.staging)vkDestroyBuffer(r.device,r.staging,nullptr); if(r.stagingMemory)vkFreeMemory(r.device,r.stagingMemory,nullptr);
    r.staging={};r.stagingMemory={};r.stagingSize=0;
    for(auto& t:r.textures){if(t.view)vkDestroyImageView(r.device,t.view,nullptr);if(t.image)vkDestroyImage(r.device,t.image,nullptr);if(t.memory)vkFreeMemory(r.device,t.memory,nullptr);t={};}
}
void destroyAll() {
    if(r.device)vkDeviceWaitIdle(r.device); destroyTextures();
    if(r.device){
        if(r.sampler)vkDestroySampler(r.device,r.sampler,nullptr); if(r.descriptorPool)vkDestroyDescriptorPool(r.device,r.descriptorPool,nullptr);
        if(r.fence)vkDestroyFence(r.device,r.fence,nullptr); if(r.acquired)vkDestroySemaphore(r.device,r.acquired,nullptr); if(r.finished)vkDestroySemaphore(r.device,r.finished,nullptr);
        if(r.commandPool)vkDestroyCommandPool(r.device,r.commandPool,nullptr);
        for(auto f:r.framebuffers)vkDestroyFramebuffer(r.device,f,nullptr); if(r.pipeline)vkDestroyPipeline(r.device,r.pipeline,nullptr);
        if(r.pipelineLayout)vkDestroyPipelineLayout(r.device,r.pipelineLayout,nullptr);if(r.setLayout)vkDestroyDescriptorSetLayout(r.device,r.setLayout,nullptr);
        if(r.renderPass)vkDestroyRenderPass(r.device,r.renderPass,nullptr);for(auto v:r.swapViews)vkDestroyImageView(r.device,v,nullptr);
        if(r.swapchain)vkDestroySwapchainKHR(r.device,r.swapchain,nullptr);vkDestroyDevice(r.device,nullptr);
    }
    if(r.surface&&r.instance)vkDestroySurfaceKHR(r.instance,r.surface,nullptr);if(r.instance)vkDestroyInstance(r.instance,nullptr);r={};
}
bool createTexture(Texture& t,int w,int h) {
    t.w=w;t.h=h;VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ci.imageType=VK_IMAGE_TYPE_2D;ci.format=VK_FORMAT_R8_UNORM;ci.extent={uint32_t(w),uint32_t(h),1};ci.mipLevels=1;ci.arrayLayers=1;ci.samples=VK_SAMPLE_COUNT_1_BIT;ci.tiling=VK_IMAGE_TILING_OPTIMAL;ci.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;ci.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if(!ok(vkCreateImage(r.device,&ci,nullptr,&t.image),"vkCreateImage"))return false;VkMemoryRequirements req{};vkGetImageMemoryRequirements(r.device,t.image,&req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=memoryType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);if(ai.memoryTypeIndex==UINT32_MAX||!ok(vkAllocateMemory(r.device,&ai,nullptr,&t.memory),"texture memory"))return false;vkBindImageMemory(r.device,t.image,t.memory,0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=t.image;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=VK_FORMAT_R8_UNORM;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};return ok(vkCreateImageView(r.device,&vi,nullptr,&t.view),"texture view");
}
bool createFrameResources(int w,int h) {
    RLOGD("createFrameResources width=%d height=%d", w, h);
    destroyTextures(); if(!createTexture(r.textures[0],w,h)||!createTexture(r.textures[1],w/2,h/2)||!createTexture(r.textures[2],w/2,h/2))return false;
    r.stagingSize=size_t(w)*h*3/2;VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=r.stagingSize;bi.usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;if(!ok(vkCreateBuffer(r.device,&bi,nullptr,&r.staging),"staging buffer"))return false;
    VkMemoryRequirements req{};vkGetBufferMemoryRequirements(r.device,r.staging,&req);VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=memoryType(req.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);if(ai.memoryTypeIndex==UINT32_MAX||!ok(vkAllocateMemory(r.device,&ai,nullptr,&r.stagingMemory),"staging memory"))return false;vkBindBufferMemory(r.device,r.staging,r.stagingMemory,0);
    std::array<VkDescriptorImageInfo,3> info{};std::array<VkWriteDescriptorSet,3> writes{};for(int i=0;i<3;i++){info[i]={r.sampler,r.textures[i].view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstSet=r.descriptor;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;writes[i].pImageInfo=&info[i];}vkUpdateDescriptorSets(r.device,3,writes.data(),0,nullptr);return true;
}
bool init(ANativeWindow* window,AAssetManager* assets) {
    RLOGD("vulkan init start window=%p assets=%p windowSize=%dx%d", window, assets,
          window ? ANativeWindow_getWidth(window) : 0,
          window ? ANativeWindow_getHeight(window) : 0);
    r.assets=assets;VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="CameraPipeline";app.apiVersion=VK_API_VERSION_1_0;const char* iext[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&app;ici.enabledExtensionCount=2;ici.ppEnabledExtensionNames=iext;if(!ok(vkCreateInstance(&ici,nullptr,&r.instance),"vkCreateInstance"))return false;
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=window;if(!ok(vkCreateAndroidSurfaceKHR(r.instance,&sci,nullptr,&r.surface),"vkCreateAndroidSurfaceKHR"))return false;
    uint32_t pc=0;vkEnumeratePhysicalDevices(r.instance,&pc,nullptr);std::vector<VkPhysicalDevice> ps(pc);vkEnumeratePhysicalDevices(r.instance,&pc,ps.data());
    for(auto p:ps){uint32_t qc=0;vkGetPhysicalDeviceQueueFamilyProperties(p,&qc,nullptr);std::vector<VkQueueFamilyProperties> qp(qc);vkGetPhysicalDeviceQueueFamilyProperties(p,&qc,qp.data());for(uint32_t i=0;i<qc;i++){VkBool32 present=false;vkGetPhysicalDeviceSurfaceSupportKHR(p,i,r.surface,&present);if((qp[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&present){r.physical=p;r.queueFamily=i;break;}}if(r.physical)break;}if(!r.physical)return false;
    float priority=1;VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qci.queueFamilyIndex=r.queueFamily;qci.queueCount=1;qci.pQueuePriorities=&priority;const char* dext=VK_KHR_SWAPCHAIN_EXTENSION_NAME;VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;dci.enabledExtensionCount=1;dci.ppEnabledExtensionNames=&dext;if(!ok(vkCreateDevice(r.physical,&dci,nullptr,&r.device),"vkCreateDevice"))return false;vkGetDeviceQueue(r.device,r.queueFamily,0,&r.queue);
    VkSurfaceCapabilitiesKHR caps{};vkGetPhysicalDeviceSurfaceCapabilitiesKHR(r.physical,r.surface,&caps);uint32_t fc=0;vkGetPhysicalDeviceSurfaceFormatsKHR(r.physical,r.surface,&fc,nullptr);std::vector<VkSurfaceFormatKHR> fs(fc);vkGetPhysicalDeviceSurfaceFormatsKHR(r.physical,r.surface,&fc,fs.data());auto sf=fs[0];for(auto f:fs)if(f.format==VK_FORMAT_R8G8B8A8_UNORM||f.format==VK_FORMAT_B8G8R8A8_UNORM){sf=f;break;}r.swapFormat=sf.format;r.extent=caps.currentExtent;r.surfaceTransform=caps.currentTransform;if(r.extent.width==UINT32_MAX)r.extent={uint32_t(ANativeWindow_getWidth(window)),uint32_t(ANativeWindow_getHeight(window))};LOGE("swapchain extent=%ux%u window=%dx%d transform=0x%x",r.extent.width,r.extent.height,ANativeWindow_getWidth(window),ANativeWindow_getHeight(window),r.surfaceTransform);RLOGD("swapchain extent=%ux%u window=%dx%d transform=0x%x format=%d",r.extent.width,r.extent.height,ANativeWindow_getWidth(window),ANativeWindow_getHeight(window),r.surfaceTransform,r.swapFormat);
    uint32_t count=std::max(2u,caps.minImageCount);if(caps.maxImageCount&&count>caps.maxImageCount)count=caps.maxImageCount;VkSwapchainCreateInfoKHR sw{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};sw.surface=r.surface;sw.minImageCount=count;sw.imageFormat=sf.format;sw.imageColorSpace=sf.colorSpace;sw.imageExtent=r.extent;sw.imageArrayLayers=1;sw.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;sw.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;sw.preTransform=caps.currentTransform;sw.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;sw.presentMode=VK_PRESENT_MODE_FIFO_KHR;sw.clipped=VK_TRUE;if(!ok(vkCreateSwapchainKHR(r.device,&sw,nullptr,&r.swapchain),"vkCreateSwapchainKHR"))return false;vkGetSwapchainImagesKHR(r.device,r.swapchain,&count,nullptr);r.swapImages.resize(count);vkGetSwapchainImagesKHR(r.device,r.swapchain,&count,r.swapImages.data());
    for(auto image:r.swapImages){VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=image;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=r.swapFormat;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};VkImageView v{};if(!ok(vkCreateImageView(r.device,&vi,nullptr,&v),"swap view"))return false;r.swapViews.push_back(v);}
    VkAttachmentDescription ad{};ad.format=r.swapFormat;ad.samples=VK_SAMPLE_COUNT_1_BIT;ad.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;ad.storeOp=VK_ATTACHMENT_STORE_OP_STORE;ad.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;ad.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;VkAttachmentReference ar{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};VkSubpassDescription sp{};sp.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sp.colorAttachmentCount=1;sp.pColorAttachments=&ar;VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};rp.attachmentCount=1;rp.pAttachments=&ad;rp.subpassCount=1;rp.pSubpasses=&sp;if(!ok(vkCreateRenderPass(r.device,&rp,nullptr,&r.renderPass),"render pass"))return false;
    for(auto v:r.swapViews){VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fi.renderPass=r.renderPass;fi.attachmentCount=1;fi.pAttachments=&v;fi.width=r.extent.width;fi.height=r.extent.height;fi.layers=1;VkFramebuffer f{};if(!ok(vkCreateFramebuffer(r.device,&fi,nullptr,&f),"framebuffer"))return false;r.framebuffers.push_back(f);}
    std::array<VkDescriptorSetLayoutBinding,3> bindings{};for(uint32_t i=0;i<3;i++){bindings[i].binding=i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;bindings[i].descriptorCount=1;bindings[i].stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;}VkDescriptorSetLayoutCreateInfo sl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};sl.bindingCount=3;sl.pBindings=bindings.data();if(!ok(vkCreateDescriptorSetLayout(r.device,&sl,nullptr,&r.setLayout),"set layout"))return false;VkPushConstantRange push{};push.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;push.offset=0;push.size=16;VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.setLayoutCount=1;pl.pSetLayouts=&r.setLayout;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&push;if(!ok(vkCreatePipelineLayout(r.device,&pl,nullptr,&r.pipelineLayout),"pipeline layout"))return false;
    VkShaderModule vs=shader("camera.vert.spv"),fsmod=shader("camera.frag.spv");if(!vs||!fsmod)return false;VkPipelineShaderStageCreateInfo stages[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;stages[0].module=vs;stages[0].pName="main";stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;stages[1].module=fsmod;stages[1].pName="main";VkPipelineVertexInputStateCreateInfo vertex{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;VkViewport vp{0,0,float(r.extent.width),float(r.extent.height),0,1};VkRect2D sc{{0,0},r.extent};VkPipelineViewportStateCreateInfo vsi{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vsi.viewportCount=1;vsi.pViewports=&vp;vsi.scissorCount=1;vsi.pScissors=&sc;VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};rs.polygonMode=VK_POLYGON_MODE_FILL;rs.cullMode=VK_CULL_MODE_NONE;rs.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;rs.lineWidth=1;VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;VkPipelineColorBlendAttachmentState cba{};cba.colorWriteMask=0xf;VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};cb.attachmentCount=1;cb.pAttachments=&cba;VkGraphicsPipelineCreateInfo gp{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};gp.stageCount=2;gp.pStages=stages;gp.pVertexInputState=&vertex;gp.pInputAssemblyState=&ia;gp.pViewportState=&vsi;gp.pRasterizationState=&rs;gp.pMultisampleState=&ms;gp.pColorBlendState=&cb;gp.layout=r.pipelineLayout;gp.renderPass=r.renderPass;if(!ok(vkCreateGraphicsPipelines(r.device,VK_NULL_HANDLE,1,&gp,nullptr,&r.pipeline),"pipeline"))return false;vkDestroyShaderModule(r.device,vs,nullptr);vkDestroyShaderModule(r.device,fsmod,nullptr);
    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;cp.queueFamilyIndex=r.queueFamily;if(!ok(vkCreateCommandPool(r.device,&cp,nullptr,&r.commandPool),"command pool"))return false;VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=r.commandPool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;vkAllocateCommandBuffers(r.device,&ca,&r.command);VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};vkCreateSemaphore(r.device,&sem,nullptr,&r.acquired);vkCreateSemaphore(r.device,&sem,nullptr,&r.finished);VkFenceCreateInfo fen{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fen.flags=VK_FENCE_CREATE_SIGNALED_BIT;vkCreateFence(r.device,&fen,nullptr,&r.fence);
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,3};VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dpi.maxSets=1;dpi.poolSizeCount=1;dpi.pPoolSizes=&poolSize;vkCreateDescriptorPool(r.device,&dpi,nullptr,&r.descriptorPool);VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};dai.descriptorPool=r.descriptorPool;dai.descriptorSetCount=1;dai.pSetLayouts=&r.setLayout;vkAllocateDescriptorSets(r.device,&dai,&r.descriptor);VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};si.magFilter=VK_FILTER_LINEAR;si.minFilter=VK_FILTER_LINEAR;si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;si.maxLod=1;vkCreateSampler(r.device,&si,nullptr,&r.sampler);r.ready=true;RLOGD("vulkan init ready");return true;
}
void render(const uint8_t* y,const uint8_t* u,const uint8_t* v,int w,int h,uint16_t rotation,bool mirror) {
    if(!r.ready){RLOGE("render skipped: renderer not ready");return;}RLOGD("render start width=%d height=%d y0=%u u0=%u v0=%u",w,h,y?y[0]:0,u?u[0]:0,v?v[0]:0);if(r.textures[0].w!=w||r.textures[0].h!=h)if(!createFrameResources(w,h)){RLOGE("render skipped: createFrameResources failed width=%d height=%d",w,h);return;}vkWaitForFences(r.device,1,&r.fence,VK_TRUE,UINT64_MAX);
    void* mapped{};vkMapMemory(r.device,r.stagingMemory,0,r.stagingSize,0,&mapped);size_t ys=size_t(w)*h,cs=ys/4;memcpy(mapped,y,ys);memcpy(static_cast<uint8_t*>(mapped)+ys,u,cs);memcpy(static_cast<uint8_t*>(mapped)+ys+cs,v,cs);vkUnmapMemory(r.device,r.stagingMemory);
    uint32_t index=0;VkResult acq=vkAcquireNextImageKHR(r.device,r.swapchain,UINT64_MAX,r.acquired,VK_NULL_HANDLE,&index);if(acq!=VK_SUCCESS&&acq!=VK_SUBOPTIMAL_KHR){RLOGE("render skipped: vkAcquireNextImageKHR result=%d",acq);return;}vkResetFences(r.device,1,&r.fence);vkResetCommandBuffer(r.command,0);VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};vkBeginCommandBuffer(r.command,&begin);
    size_t offsets[3]={0,ys,ys+cs};for(int i=0;i<3;i++){VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.srcAccessMask=0;b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.image=r.textures[i].image;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};vkCmdPipelineBarrier(r.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&b);VkBufferImageCopy c{};c.bufferOffset=offsets[i];c.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};c.imageExtent={uint32_t(r.textures[i].w),uint32_t(r.textures[i].h),1};vkCmdCopyBufferToImage(r.command,r.staging,r.textures[i].image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&c);b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;vkCmdPipelineBarrier(r.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&b);}
    VkClearValue clear{{{0,0,0,1}}};VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rp.renderPass=r.renderPass;rp.framebuffer=r.framebuffers[index];rp.renderArea={{0,0},r.extent};rp.clearValueCount=1;rp.pClearValues=&clear;vkCmdBeginRenderPass(r.command,&rp,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(r.command,VK_PIPELINE_BIND_POINT_GRAPHICS,r.pipeline);vkCmdBindDescriptorSets(r.command,VK_PIPELINE_BIND_POINT_GRAPHICS,r.pipelineLayout,0,1,&r.descriptor,0,nullptr);struct Push{float scale[2];int32_t rotation;int32_t mirror;}push{{1.0f,1.0f},int32_t(rotation),mirror?1:0};const bool imageQuarterTurn=rotation==90||rotation==270;const float imageAspect=imageQuarterTurn?float(h)/float(w):float(w)/float(h);const bool surfaceQuarterTurn=r.surfaceTransform==VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR||r.surfaceTransform==VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR||r.surfaceTransform==VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR||r.surfaceTransform==VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR;const float screenAspect=surfaceQuarterTurn?float(r.extent.height)/float(r.extent.width):float(r.extent.width)/float(r.extent.height);RLOGD("render scale fill imageAspect=%f screenAspect=%f scale=%f,%f rotation=%u mirror=%d",imageAspect,screenAspect,push.scale[0],push.scale[1],rotation,mirror?1:0);vkCmdPushConstants(r.command,r.pipelineLayout,VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(push),&push);vkCmdDraw(r.command,3,1,0,0);vkCmdEndRenderPass(r.command);vkEndCommandBuffer(r.command);
    VkPipelineStageFlags waitStage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&r.acquired;submit.pWaitDstStageMask=&waitStage;submit.commandBufferCount=1;submit.pCommandBuffers=&r.command;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&r.finished;VkResult submitResult=vkQueueSubmit(r.queue,1,&submit,r.fence);if(submitResult!=VK_SUCCESS){RLOGE("render skipped: vkQueueSubmit result=%d",submitResult);return;}VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.waitSemaphoreCount=1;present.pWaitSemaphores=&r.finished;present.swapchainCount=1;present.pSwapchains=&r.swapchain;present.pImageIndices=&index;VkResult presentResult=vkQueuePresentKHR(r.queue,&present);RLOGD("render complete imageIndex=%u presentResult=%d",index,presentResult);
}
}
std::string vulkanSetWindow(ANativeWindow* window,AAssetManager* assets){std::lock_guard<std::mutex> lock(mutex);RLOGD("vulkanSetWindow window=%p assets=%p",window,assets);destroyAll();if(!window||!assets){RLOGD("vulkanSetWindow released");return "Vulkan surface released";}if(!init(window,assets)){destroyAll();RLOGE("vulkanSetWindow failed initialization");return "Error: Vulkan initialization failed (see CameraVulkan log)";}std::ostringstream s;s<<"Vulkan SurfaceView ready | swapchain "<<r.extent.width<<"x"<<r.extent.height;RLOGD("%s",s.str().c_str());return s.str();}
void vulkanDestroy(){std::lock_guard<std::mutex> lock(mutex);RLOGD("vulkanDestroy");destroyAll();}
void vulkanSubmitYuv420(const uint8_t* y,const uint8_t* u,const uint8_t* v,int w,int h,uint16_t rotation,bool mirror){std::lock_guard<std::mutex> lock(mutex);RLOGD("vulkanSubmitYuv420 width=%d height=%d",w,h);render(y,u,v,w,h,rotation,mirror);}
